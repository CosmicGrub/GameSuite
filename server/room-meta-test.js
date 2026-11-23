// Wave 5, slice S0 + S1: room metadata, peek, room_full/locked/started refusals, lock, kick, avatars, display-name
// sanitising, lobby TTL, room-code cooldown, health endpoint. Self-contained: spawns its own relays (see test-lib.js).
const { createHarness } = require('./test-lib')

const h = createHarness('room-meta-test')
const { check, isType, isError } = h

const HOST_AVATAR = 'pip|sky|circle'

async function metadataAndPeek(relay) {
  const host = await h.hostRoom(relay, 'rm-host', { displayName: 'Sam', avatar: HOST_AVATAR, gameId: 'chess', maxSeats: 2, minSeats: 2, public: false })
  check('[meta] hosted echoes gameId/minSeats/maxSeats and tells the UI when the lobby expires',
    host.hosted.gameId === 'chess' && host.hosted.minSeats === 2 && host.hosted.maxSeats === 2 && host.hosted.expiresInMs === 600000 && host.hosted.reconnectToken === host.token, host.hosted)

  const legacy = await h.hostRoom(relay, 'rm-legacy')
  check('[meta] a legacy host (no gameId, an old client) gets the exact old hosted shape',
    JSON.stringify(Object.keys(legacy.hosted).sort()) === JSON.stringify(['playerId', 'reconnectToken', 'roomCode', 'type']), legacy.hosted)

  const peeker = await h.client(relay)
  const peek = await peeker.request({ type: 'peek', roomCode: host.roomCode }, isType('room'))
  check('[peek] shape: type room, roomCode, gameId, seatsTaken, minSeats, maxSeats, state, hostName and nothing else',
    !!peek && JSON.stringify(Object.keys(peek).sort()) === JSON.stringify(['gameId', 'hostName', 'maxSeats', 'minSeats', 'roomCode', 'seatsTaken', 'state', 'type']), peek)
  check('[peek] values: chess, 1 of 2 seats, lobby, host name Sam', !!peek && peek.gameId === 'chess' && peek.seatsTaken === 1 && peek.maxSeats === 2 && peek.state === 'lobby' && peek.hostName === 'Sam', peek)
  check('[peek] no token, player id or avatar leaks into the raw frame',
    !peeker.raw.some((t) => t.includes(host.token) || t.includes('rm-host') || t.includes(HOST_AVATAR)), peeker.raw)
  const lower = await peeker.request({ type: 'peek', roomCode: host.roomCode.toLowerCase() }, isType('room'))
  check('[peek] the code is case-insensitive like join', !!lower && lower.roomCode === host.roomCode)
  const legacyPeek = await peeker.request({ type: 'peek', roomCode: legacy.roomCode }, isType('room'))
  check('[peek] a legacy room previews with gameId null and the relay-wide seat cap', !!legacyPeek && legacyPeek.gameId === null && legacyPeek.maxSeats === 8 && legacyPeek.minSeats === 2, legacyPeek)
  const miss = await peeker.request({ type: 'peek', roomCode: 'ZZZZ' }, (m) => m.type === 'error')
  check('[peek] an unknown room is error no_such_room', !!miss && miss.code === 'no_such_room' && miss.message === 'Room not found', miss)
  check('[peek] peek did not seat anyone: the host still sees only itself and no playerJoined', host.c.of('playerJoined').length === 0)
  const bad = await peeker.request({ type: 'peek' }, (m) => m.type === 'error')
  check('[peek] a peek without roomCode is bad_request', !!bad && bad.code === 'bad_request', bad)

  for (const [label, extra] of [
    ['a malformed gameId', { gameId: 'Bad Id!' }],
    ['a non-string gameId', { gameId: 7 }],
    ['a string maxSeats', { gameId: 'chess', maxSeats: '2' }],
    ['a fractional minSeats', { gameId: 'chess', minSeats: 1.5 }],
    ['a non-boolean public', { gameId: 'chess', public: 'yes' }],
    ['maxSeats without gameId', { maxSeats: 2 }],
  ]) {
    const c = await h.client(relay)
    const r = await c.request({ type: 'host', playerId: 'rm-bad', ...extra }, (m) => m.type === 'hosted' || m.type === 'error')
    check(`[meta] ${label} is refused with bad_metadata and no room is made`, !!r && r.type === 'error' && r.code === 'bad_metadata', r)
  }
  const big = await h.hostRoom(relay, 'rm-big', { gameId: 'uno', maxSeats: 99, minSeats: 0 })
  check('[meta] maxSeats clamps to the relay cap (8) and minSeats to at least 1', big.hosted.maxSeats === 8 && big.hosted.minSeats === 1, big.hosted)
  const zero = await h.hostRoom(relay, 'rm-zero', { gameId: 'uno', maxSeats: 0 })
  check('[meta] maxSeats 0 clamps to 1', zero.hosted.maxSeats === 1, zero.hosted)
}

async function fullLockedStartedKick(relay) {
  const host = await h.hostRoom(relay, 'fl-host', { displayName: 'Hana', gameId: 'chess', maxSeats: 2 })

  const wrong = await h.joinRoom(relay, host.roomCode, 'fl-wrong', { gameId: 'checkers' })
  check('[wrong_game] joining with another gameId is refused with wrong_game', !!wrong.error && wrong.error.code === 'wrong_game', wrong.error)
  const right = await h.joinRoom(relay, host.roomCode, 'fl-guest', { displayName: 'Gus', gameId: 'chess', avatar: 'robo|lime|square' })
  check('[join] the matching gameId joins; joined carries the host id and players', !!right.joined && right.joined.hostPlayerId === 'fl-host' && right.joined.players.length === 1, right.error || right.joined)
  check('[avatar] the joiner sees the host avatar... (host sent none, so the field is absent)', right.joined && right.joined.players[0].avatar === undefined, right.joined)
  const joinedFrame = host.c.of('playerJoined')[0]
  check('[avatar] a valid avatar is relayed in playerJoined', !!joinedFrame && joinedFrame.avatar === 'robo|lime|square' && joinedFrame.displayName === 'Gus', joinedFrame)

  const full = await h.joinRoom(relay, host.roomCode, 'fl-third')
  check('[room_full] a third player is refused with room_full at maxSeats 2, with no Hello needed', !!full.error && full.error.code === 'room_full' && full.error.message === 'Room is full', full.error)
  check('[room_full] the refused joiner was never announced to the room', !host.c.of('playerJoined').some((m) => m.playerId === 'fl-third') && !right.c.of('playerJoined').some((m) => m.playerId === 'fl-third'))
  const peekFull = await (await h.client(relay)).request({ type: 'peek', roomCode: host.roomCode }, isType('room'))
  check('[peek] a full room reports 2 of 2 seats', !!peekFull && peekFull.seatsTaken === 2 && peekFull.maxSeats === 2, peekFull)

  const spec = await h.joinRoom(relay, host.roomCode, 'fl-spec', { spectator: true })
  check('[room_full] a spectator is unaffected by the seat cap', !!spec.joined, spec.error)

  // lock / started are host only
  for (const [who, member] of [['a guest', right], ['a spectator', spec]]) {
    for (const frame of [{ type: 'lock', locked: true }, { type: 'started' }, { type: 'kick', playerId: 'fl-host' }, { type: 'kick', playerId: 'fl-guest' }]) {
      const r = await member.c.request(frame, (m) => m.type === 'error' || m.type === 'roomState')
      check(`[host-only] ${who} cannot send ${frame.type}${frame.playerId ? ' ' + frame.playerId : ''}: not_host`, !!r && r.type === 'error' && r.code === 'not_host', r)
    }
  }
  const stranger = await h.client(relay)
  const noRoom = await stranger.request({ type: 'lock', locked: true }, (m) => m.type === 'error')
  check('[host-only] a socket in no room gets not_in_room', !!noRoom && noRoom.code === 'not_in_room', noRoom)
  const peekAfter = await stranger.request({ type: 'peek', roomCode: host.roomCode }, isType('room'))
  check('[host-only] nothing changed: the room is still in state lobby with both seats', !!peekAfter && peekAfter.state === 'lobby' && peekAfter.seatsTaken === 2, peekAfter)
  check('[host-only] the guest was not kicked by its own attempt', right.c.of('kicked').length === 0 && !host.c.of('playerLeft').length)
  const forged = await right.c.request({ type: 'lock', locked: true, playerId: 'fl-host', hostPlayerId: 'fl-host' }, (m) => m.type === 'error' || m.type === 'roomState')
  check('[host-only] naming the host inside the frame does not make a guest the host', !!forged && forged.code === 'not_host', forged)

  // lock
  const badLock = await host.c.request({ type: 'lock', locked: 'yes' }, (m) => m.type === 'error' || m.type === 'roomState')
  check('[lock] a non-boolean locked is bad_request', !!badLock && badLock.code === 'bad_request', badLock)
  const lockAck = await host.c.request({ type: 'lock', locked: true }, isType('roomState'))
  check('[lock] the host gets a roomState ack: locked', !!lockAck && lockAck.state === 'locked' && lockAck.seatsTaken === 2 && lockAck.maxSeats === 2 && lockAck.roomCode === host.roomCode, lockAck)
  const peekLocked = await stranger.request({ type: 'peek', roomCode: host.roomCode }, isType('room'))
  check('[lock] peek reports state locked', !!peekLocked && peekLocked.state === 'locked', peekLocked)
  // free a seat so room_locked (not room_full) is what refuses the next join
  const kickAck = host.c.mark()
  right.c.send({ type: 'leave' })
  await host.c.waitFor((m) => m.type === 'playerLeft' && m.playerId === 'fl-guest', { since: kickAck })
  const locked = await h.joinRoom(relay, host.roomCode, 'fl-late')
  check('[lock] a locked room refuses a new player with room_locked', !!locked.error && locked.error.code === 'room_locked', locked.error)
  const lockedSpec = await h.joinRoom(relay, host.roomCode, 'fl-late-spec', { spectator: true })
  check('[lock] a locked room still admits a spectator (the host decides on Hello)', !!lockedSpec.joined, lockedSpec.error)
  await host.c.request({ type: 'lock', locked: false }, isType('roomState'))
  const unlocked = await h.joinRoom(relay, host.roomCode, 'fl-after-unlock')
  check('[lock] unlocking admits players again', !!unlocked.joined, unlocked.error)

  // kick
  const k = await host.c.request({ type: 'kick', playerId: 'nobody' }, (m) => m.type === 'error')
  check('[kick] an unknown player is no_such_player', !!k && k.code === 'no_such_player', k)
  const kh = await host.c.request({ type: 'kick', playerId: 'fl-host' }, (m) => m.type === 'error')
  check('[kick] the host cannot kick itself: cannot_kick_host', !!kh && kh.code === 'cannot_kick_host', kh)
  const kb = await host.c.request({ type: 'kick' }, (m) => m.type === 'error')
  check('[kick] a kick without playerId is bad_request', !!kb && kb.code === 'bad_request', kb)
  const before = host.c.mark()
  const kickTarget = unlocked
  const targetMark = kickTarget.c.mark()
  host.c.send({ type: 'kick', playerId: 'fl-after-unlock' })
  const kickedFrame = await kickTarget.c.waitFor(isType('kicked'), { since: targetMark })
  check('[kick] the target is told: kicked', !!kickedFrame)
  const leftFrame = await host.c.waitFor((m) => m.type === 'playerLeft' && m.playerId === 'fl-after-unlock', { since: before })
  check('[kick] the host (and the room) sees playerLeft for the kicked seat', !!leftFrame)
  const rejoin = await h.joinRoom(relay, host.roomCode, 'fl-after-unlock')
  check('[kick] the kicked id is retired: it cannot rejoin the room (player_retired)', !!rejoin.error && rejoin.error.code === 'player_retired' && rejoin.error.message === 'playerId can no longer be used in this room', rejoin.error)
  const rejoinSpec = await h.joinRoom(relay, host.roomCode, 'fl-after-unlock', { spectator: true })
  check('[kick] ...nor spectate under it', !!rejoinSpec.error && rejoinSpec.error.code === 'player_retired', rejoinSpec.error)
  const speakMark = host.c.mark()
  kickTarget.c.send({ type: 'message', toPlayerId: null, payloadBase64: 'QUJD' })
  const speakErr = await kickTarget.c.waitFor(isType('error'), { since: targetMark })
  check('[kick] the kicked socket is inert: it cannot speak into the room (not in a room)', !!speakErr && speakErr.message === 'Not in a room — host or join first' && !host.c.of('message', speakMark).length, speakErr)
  const fresh = await h.joinRoom(relay, host.roomCode, 'fl-brand-new-id')
  check('[kick] kick is a courtesy, not a ban: a brand-new id can join (lock exists for a real "no more")', !!fresh.joined, fresh.error)
  // kicking a spectator retires its id too
  const sm = host.c.mark()
  host.c.send({ type: 'kick', playerId: 'fl-spec' })
  const specKicked = await spec.c.waitFor(isType('kicked'))
  check('[kick] a spectator can be kicked as well', !!specKicked)
  const specBack = await h.joinRoom(relay, host.roomCode, 'fl-spec', { spectator: true })
  check('[kick] a kicked spectator id cannot come back', !!specBack.error && specBack.error.code === 'player_retired', specBack.error)

  // started
  const startAck = await host.c.request({ type: 'started' }, isType('roomState'))
  check('[started] the host gets a roomState ack: started', !!startAck && startAck.state === 'started', startAck)
  const peekStarted = await stranger.request({ type: 'peek', roomCode: host.roomCode }, isType('room'))
  check('[started] peek reports state started', !!peekStarted && peekStarted.state === 'started', peekStarted)
  await host.c.request({ type: 'lock', locked: false }, isType('roomState'))
  const startedJoin = await h.joinRoom(relay, host.roomCode, 'fl-too-late')
  check('[started] a started room refuses new players with room_started (even with free seats and unlocked)', !!startedJoin.error && startedJoin.error.code === 'room_started', startedJoin.error)
  // an existing seat can still resume after started
  fresh.c.ws.terminate()
  await host.c.waitFor((m) => m.type === 'playerDisconnected' && m.playerId === 'fl-brand-new-id')
  const resumeC = await h.client(relay)
  const resumed = await resumeC.request({ type: 'join', roomCode: host.roomCode, playerId: 'fl-brand-new-id', reconnectToken: fresh.token }, (m) => m.type === 'reconnected' || m.type === 'error')
  check('[started] a seated player can still reconnect into a started room', !!resumed && resumed.type === 'reconnected', resumed)
}

async function namesAndAvatars(relay) {
  const host = await h.hostRoom(relay, 'na-host', { gameId: 'uno', maxSeats: 8, displayName: '  Zoe\u0000\n\t  the   Great‮​  ' })
  const g = await h.joinRoom(relay, host.roomCode, 'na-g1', { displayName: 'A'.repeat(100), avatar: 'pip|sky|circle' })
  check('[name] control characters become spaces, whitespace collapses, bidi/zero-width characters vanish', g.joined && g.joined.players[0].displayName === 'Zoe the Great', g.joined)
  const joinedAnnounce = host.c.of('playerJoined').find((m) => m.playerId === 'na-g1')
  check('[name] a long name is truncated to 40 characters', !!joinedAnnounce && joinedAnnounce.displayName === 'A'.repeat(40), joinedAnnounce)
  const emoji = '\u{1F600}'.repeat(60)
  const g2 = await h.joinRoom(relay, host.roomCode, 'na-g2', { displayName: emoji })
  const e = host.c.of('playerJoined').find((m) => m.playerId === 'na-g2')
  check('[name] truncation counts code points and never splits a surrogate pair', !!e && Array.from(e.displayName).length === 40 && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(e.displayName), e && e.displayName.length)
  const g3 = await h.joinRoom(relay, host.roomCode, 'na-g3', { displayName: '\u0007 ​ \t' })
  const blank = host.c.of('playerJoined').find((m) => m.playerId === 'na-g3')
  check('[name] a name that is nothing but controls falls back to the playerId', !!blank && blank.displayName === 'na-g3', blank)
  const g4 = await h.joinRoom(relay, host.roomCode, 'na-g4', { displayName: 12345 })
  const num = host.c.of('playerJoined').find((m) => m.playerId === 'na-g4')
  check('[name] a non-string name falls back to the playerId', !!num && num.displayName === 'na-g4', num)

  const bads = ['PIP|sky|circle', 'pip|sky', 'pip|sky|circle|x', 'a|b|c', 'pip|sky|' + 'x'.repeat(40), 7, { a: 1 }, 'pip|sky|circ1e']
  let allDropped = true
  let i = 0
  for (const bad of bads) {
    // one room per attempt: a room seats at most 8, and each attempt needs its own announcement to read
    const badHost = await h.hostRoom(relay, `na-badhost${i}`, { gameId: 'uno', maxSeats: 8 })
    const gx = await h.joinRoom(relay, badHost.roomCode, `na-bad${i}`, { avatar: bad })
    const frame = badHost.c.of('playerJoined').find((m) => m.playerId === `na-bad${i}`) || (await badHost.c.waitFor((m) => m.type === 'playerJoined'))
    if (!gx.joined || !frame || frame.avatar !== undefined) allDropped = false
    i++
  }
  check('[avatar] malformed avatars are dropped silently (no avatar field, join still succeeds)', allDropped)
  const late = await h.joinRoom(relay, host.roomCode, 'na-late')
  const players = late.joined.players
  check('[avatar] the roster a joiner receives carries each valid avatar and omits the rest', players.find((p) => p.playerId === 'na-g1').avatar === 'pip|sky|circle' && players.find((p) => p.playerId === 'na-g2').avatar === undefined, players.slice(0, 3))
  const hostAv = await h.hostRoom(relay, 'na-avhost', { gameId: 'uno', avatar: 'cat|rose|hex' })
  const guestAv = await h.joinRoom(relay, hostAv.roomCode, 'na-avguest')
  check('[avatar] the host avatar reaches a joiner in joined.players', guestAv.joined.players[0].avatar === 'cat|rose|hex', guestAv.joined)
  check('[avatar] only the avatar string is added: no other profile field is ever on a roster entry',
    guestAv.joined.players.every((p) => Object.keys(p).every((k) => ['playerId', 'displayName', 'avatar'].includes(k))))

  // a reconnect refreshes the avatar and still announces nothing extra
  guestAv.c.ws.terminate()
  await hostAv.c.waitFor((m) => m.type === 'playerDisconnected' && m.playerId === 'na-avguest')
  const rc = await h.client(relay)
  const re = await rc.request({ type: 'join', roomCode: hostAv.roomCode, playerId: 'na-avguest', reconnectToken: guestAv.token, avatar: 'dog|gold|circle' }, (m) => m.type === 'reconnected' || m.type === 'error')
  check('[avatar] a reconnect is still a reconnect', !!re && re.type === 'reconnected', re)
  const later = await h.joinRoom(relay, hostAv.roomCode, 'na-after-reconnect')
  check('[avatar] ...and the resumed seat\'s new avatar shows in later rosters', later.joined.players.find((p) => p.playerId === 'na-avguest').avatar === 'dog|gold|circle', later.joined)
}

async function lobbyTtl(relay) {
  const ctl = await h.controller(relay)
  const alone = await h.hostRoom(relay, 'ttl-alone', { gameId: 'chess', maxSeats: 2 })
  const legacy = await h.hostRoom(relay, 'ttl-legacy')
  const withGuest = await h.hostRoom(relay, 'ttl-withguest', { gameId: 'chess', maxSeats: 2 })
  const guest = await h.joinRoom(relay, withGuest.roomCode, 'ttl-guest')
  const started = await h.hostRoom(relay, 'ttl-started', { gameId: 'chess', maxSeats: 2 })
  await started.c.request({ type: 'started' }, isType('roomState'))
  const guestOnly = await h.hostRoom(relay, 'ttl-guestleft', { gameId: 'chess', maxSeats: 2 })
  const leaver = await h.joinRoom(relay, guestOnly.roomCode, 'ttl-leaver')

  await ctl.advance(9 * 60 * 1000)
  const probe = await h.client(relay)
  const still = await probe.request({ type: 'peek', roomCode: alone.roomCode }, isType('room'))
  check('[ttl] after 9 minutes (< 10) the lonely lobby is still open', !!still)

  await ctl.advance(2 * 60 * 1000) // 11 minutes total
  const closed = await alone.c.waitFor(isType('roomClosed'))
  check('[ttl] after 11 minutes the host of a guest-less lobby is told roomClosed (lobby_expired)', !!closed && closed.reason === 'lobby_expired', closed)
  const gone = await probe.request({ type: 'peek', roomCode: alone.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[ttl] ...and the room no longer exists', !!gone && gone.type === 'error' && gone.code === 'no_such_room', gone)
  const l = await probe.request({ type: 'peek', roomCode: legacy.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[ttl] a legacy (old client) room is NOT subject to the lobby TTL', !!l && l.type === 'room', l)
  const w = await probe.request({ type: 'peek', roomCode: withGuest.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[ttl] a lobby that has a guest is not closed by the lobby TTL', !!w && w.type === 'room', w)
  const s = await probe.request({ type: 'peek', roomCode: started.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[ttl] a started room keeps the long idle rule, not the lobby TTL', !!s && s.type === 'room', s)
  // a guest who leaves makes the lobby guest-less again and restarts the clock
  leaver.c.send({ type: 'leave' })
  await guestOnly.c.waitFor((m) => m.type === 'playerLeft')
  await ctl.advance(5 * 60 * 1000)
  const g1 = await probe.request({ type: 'peek', roomCode: guestOnly.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[ttl] the TTL clock restarts when the last guest leaves (5 more minutes: still open)', !!g1 && g1.type === 'room', g1)
  await ctl.advance(6 * 60 * 1000)
  const g2 = await probe.request({ type: 'peek', roomCode: guestOnly.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[ttl] ...and it expires 10 minutes after that', !!g2 && g2.type === 'error', g2)

  await ctl.advance(25 * 60 * 1000) // 30+ minutes of no traffic overall
  const idleGone = await probe.request({ type: 'peek', roomCode: withGuest.roomCode }, (m) => m.type === 'error' || m.type === 'room')
  check('[ttl] the original 30-minute idle reap still applies to a lobby with guests', !!idleGone && idleGone.type === 'error', idleGone)
  const rehost = await alone.c.request({ type: 'host', playerId: 'ttl-alone-2', gameId: 'chess', maxSeats: 2 }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[ttl] a socket whose lobby expired can host again', !!rehost && rehost.type === 'hosted', rehost)
}

async function codeCooldown(relay) {
  // 32 possible codes (ROOM_CODE_LENGTH_OVERRIDE=1) and a 60 s cooldown: freed codes cool off, so hosting and
  // leaving repeatedly must eventually exhaust the code space; advancing the clock past the cooldown frees it again.
  const ctl = await h.controller(relay)
  const c = await h.client(relay)
  const seen = new Set()
  let exhausted = 0
  for (let i = 0; i < 5000 && seen.size < 32; i++) {
    const r = await c.request({ type: 'host', playerId: 'cd-host' }, (m) => m.type === 'hosted' || m.type === 'error')
    if (r && r.type === 'error' && r.code === 'no_room_code') { exhausted++; continue } // a few free codes left but 50 draws all hit cooling ones
    if (!r || r.type !== 'hosted') { check('[cooldown] hosting failed for another reason', false, r); return }
    if (seen.has(r.roomCode)) { check('[cooldown] a freed code was handed out again inside its cooldown', false, r.roomCode); return }
    seen.add(r.roomCode)
    c.send({ type: 'leave' })
  }
  check('[cooldown] freed codes are never reused inside their cooldown: all 32 codes were handed out exactly once', seen.size === 32, { distinct: seen.size })
  const stuck = await c.request({ type: 'host', playerId: 'cd-host' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[cooldown] with every code cooling, hosting fails with no_room_code until the cooldown passes', !!stuck && stuck.type === 'error' && stuck.code === 'no_room_code', stuck)
  void exhausted
  await ctl.advance(61 * 1000)
  const after = await c.request({ type: 'host', playerId: 'cd-host' }, (m) => m.type === 'hosted' || m.type === 'error')
  check('[cooldown] after 61 seconds the codes are free again', !!after && after.type === 'hosted', after)
}

async function health(relay) {
  const res = await h.httpGet(relay, '/health')
  let body = null
  try { body = JSON.parse(res.body) } catch { /* checked below */ }
  check('[ops] GET /health answers 200 JSON with status ok and counts only', res.status === 200 && !!body && body.status === 'ok' && typeof body.rooms === 'number' && typeof body.tickets === 'number', res)
  check('[ops] /health exposes counts only: no room code, player id or token field', !!body && Object.keys(body).every((k) => ['status', 'uptimeSec', 'rooms', 'tickets', 'queued', 'pendingMatches', 'queues'].includes(k)), res.body)
  const nf = await h.httpGet(relay, '/anything-else')
  check('[ops] any other path is a plain 404', nf.status === 404, nf.status)
}

h.run(async () => {
  const relay = await h.startRelay('meta')
  await health(relay)
  await metadataAndPeek(relay)
  await fullLockedStartedKick(relay)
  await namesAndAvatars(relay)
  await lobbyTtl(relay)
  const cooldownRelay = await h.startRelay('cooldown', { ROOM_CODE_LENGTH_OVERRIDE: '1', CODE_COOLDOWN_MS: '60000' })
  await codeCooldown(cooldownRelay)
})
