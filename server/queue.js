// Matchmaking (wave 5, slice S7): a small in-memory queue that groups strangers who want the same game and hands
// each group to the EXISTING host-authoritative flow. The queue never looks at game traffic; its only output is a
// room (created through deps.createRoom), a claim token per player and the frames that tell the clients about
// them. Nothing is written to disk and a ticket dies with its socket.
//
// Design: docs/roadmap/WAVE-5-MULTIPLAYER-DESIGN.md section 2. Frames: see CHANGELOG-wave5.md.
//
// The matchmaker is constructed with every external dependency injected (clock, config, seat ranges, and the
// callbacks that touch sockets and rooms) so queue-test.js can drive the algorithm in-process with a fake clock,
// and the relay drives the very same code with the injectable clock of clock.js.
const crypto = require('crypto')
const { sanitizeDisplayName, sanitizeAvatar } = require('./sanitize')

const GAME_ID_RE = /^[a-z0-9-]{1,32}$/
const OPTIONS_KEY_RE = /^[A-Za-z0-9._+-]{1,32}$/
const MAX_PLAYER_ID_LENGTH = 64
const FILL_AFTER_MIN_MS = 5000
const FILL_AFTER_MAX_MS = 120000
const CLAIM_TOKEN_BYTES = 24 // 192 bits, same as a reconnectToken

function bad(code, message, extra) {
  return { ok: false, code, message, ...(extra || {}) }
}

function isInt(v) {
  return typeof v === 'number' && Number.isInteger(v)
}

function clamp(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v))
}

function newToken() {
  return crypto.randomBytes(CLAIM_TOKEN_BYTES).toString('base64url')
}

function tokensMatch(expected, provided) {
  if (typeof expected !== 'string' || typeof provided !== 'string') return false
  const a = Buffer.from(expected)
  const b = Buffer.from(provided)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

/** Validates a 'queue' frame against the generated seat ranges. Returns {ok:true, params} or {ok:false, code, message}.
 *  The frame's seat counts are only a request: they are clamped into the game's known Online range, so a forged
 *  range can never create a table the game cannot seat. */
function parseQueueFrame(msg, seatRanges) {
  if (typeof msg.playerId !== 'string' || !msg.playerId) return bad('bad_request', 'queue requires playerId')
  if (msg.playerId.length > MAX_PLAYER_ID_LENGTH) return bad('bad_request', `playerId must be at most ${MAX_PLAYER_ID_LENGTH} characters`)
  if (typeof msg.gameId !== 'string' || !GAME_ID_RE.test(msg.gameId)) return bad('bad_request', 'queue requires a gameId matching [a-z0-9-]{1,32}')
  const range = seatRanges.games[msg.gameId]
  if (!range) return bad('unknown_game', 'This game has no online quick match')
  let optionsKey = 'std'
  if (msg.optionsKey !== undefined && msg.optionsKey !== null) {
    if (typeof msg.optionsKey !== 'string' || !OPTIONS_KEY_RE.test(msg.optionsKey)) return bad('bad_request', 'optionsKey must match [A-Za-z0-9._+-]{1,32}')
    optionsKey = msg.optionsKey
  }
  let maxSeats = range.max
  if (msg.maxSeats !== undefined && msg.maxSeats !== null) {
    if (!isInt(msg.maxSeats)) return bad('bad_request', 'maxSeats must be an integer')
    maxSeats = clamp(msg.maxSeats, range.min, range.max)
  }
  let minSeats = range.min
  if (msg.minSeats !== undefined && msg.minSeats !== null) {
    if (!isInt(msg.minSeats)) return bad('bad_request', 'minSeats must be an integer')
    minSeats = clamp(msg.minSeats, range.min, maxSeats)
  }
  let bucket = 1
  if (msg.bucket !== undefined && msg.bucket !== null) {
    if (!isInt(msg.bucket) || msg.bucket < 0 || msg.bucket > 2) return bad('bad_request', 'bucket must be 0, 1 or 2')
    bucket = msg.bucket
  }
  let fillAfterMs = null
  if (msg.fillAfterMs !== undefined && msg.fillAfterMs !== null) {
    if (!isInt(msg.fillAfterMs)) return bad('bad_request', 'fillAfterMs must be an integer or null')
    fillAfterMs = clamp(msg.fillAfterMs, FILL_AFTER_MIN_MS, FILL_AFTER_MAX_MS)
  }
  let canHost = true
  if (msg.canHost !== undefined && msg.canHost !== null) {
    if (typeof msg.canHost !== 'boolean') return bad('bad_request', 'canHost must be a boolean')
    canHost = msg.canHost
  }
  return {
    ok: true,
    params: {
      playerId: msg.playerId,
      displayName: sanitizeDisplayName(msg.displayName, msg.playerId),
      avatar: sanitizeAvatar(msg.avatar),
      gameId: msg.gameId,
      optionsKey,
      minSeats,
      maxSeats,
      bucket,
      fillAfterMs,
      canHost,
    },
  }
}

/**
 * @param {object} o
 * @param {() => number} o.now                      clock
 * @param {object} o.config                         config.js values (queue*)
 * @param {object} o.seatRanges                     seat-ranges.json
 * @param {object} o.deps
 *   send(ticket, frame)                              deliver a frame to the ticket's socket (no-op if it is closed)
 *   rttOf(ticket) -> number                          server-measured RTT in ms, Infinity when unknown
 *   createRoom({hostPlayerId, gameId, maxSeats, tickets}) -> roomCode | null   reserve the (empty) room
 *   abortRoom(roomCode)                              discard a reserved room and unseat anyone already claimed
 *   canFormRoom() -> boolean                         false while the relay is at its room cap
 *   measureRtt(ws)                                   optional: ask the socket for a fresh ping
 *   log(...args)                                     optional
 */
function createMatchmaker({ now, config, seatRanges, deps }) {
  const byWs = new Map() // ws -> ticket (every ticket that is queued or matched but not fully seated)
  const playerIds = new Set() // playerIds of tickets in byWs: one live ticket per playerId
  const queues = new Map() // queueKey -> Ticket[] sorted by enqueuedAt, oldest first
  const pending = new Map() // matchId -> Match
  const cooldownUntil = new WeakMap() // ws -> time before which it may not queue again (no-show penalty, socket-scoped only)
  let nextMatchId = 1

  const log = deps.log || (() => {})

  function queueKey(t) {
    return `${t.gameId}|${t.optionsKey}|${t.maxSeats}`
  }

  function insertSorted(t) {
    const key = queueKey(t)
    let arr = queues.get(key)
    if (!arr) queues.set(key, (arr = []))
    let i = arr.length
    while (i > 0 && arr[i - 1].enqueuedAt > t.enqueuedAt) i--
    arr.splice(i, 0, t)
    return arr
  }

  function removeFromQueue(t) {
    const key = queueKey(t)
    const arr = queues.get(key)
    if (!arr) return
    const i = arr.indexOf(t)
    if (i >= 0) arr.splice(i, 1)
    if (arr.length === 0) queues.delete(key)
  }

  function forget(t) {
    if (byWs.get(t.ws) === t) byWs.delete(t.ws)
    playerIds.delete(t.playerId)
  }

  /** Skill-bucket window by wait: same bucket only, then +-1, then anything. Computed from the OLDEST ticket of a
   *  candidate group, so a newcomer never makes an old ticket wait longer. */
  function windowFor(ageMs) {
    if (ageMs < config.queueWindow1Ms) return 0
    if (ageMs < config.queueWindow2Ms) return 1
    return 2
  }

  function seatView(t) {
    const seat = { playerId: t.playerId, displayName: t.displayName }
    if (t.avatar) seat.avatar = t.avatar
    return seat
  }

  function rtt(t) {
    const v = deps.rttOf(t)
    return typeof v === 'number' && Number.isFinite(v) ? v : Infinity
  }

  /** Lowest server-measured RTT among tickets that can host; ties go to the older ticket. */
  function chooseHost(group) {
    let best = null
    for (const t of group) {
      if (!t.canHost) continue
      if (best === null) { best = t; continue }
      const a = rtt(t)
      const b = rtt(best)
      if (a < b || (a === b && t.enqueuedAt < best.enqueuedAt)) best = t
    }
    return best
  }

  /** The group (and number of CPU seats) the oldest ticket `anchor` can start now, or null. */
  function buildGroup(queue, anchor, nowMs) {
    const age = nowMs - anchor.enqueuedAt
    const win = windowFor(age)
    const need = anchor.maxSeats
    const eligible = queue.filter((t) => t !== anchor && Math.abs(t.bucket - anchor.bucket) <= win)
    if (eligible.length + 1 >= need) {
      // A full table of humans. FIFO among the eligible; make sure somebody at it can host.
      const group = [anchor, ...eligible.slice(0, need - 1)]
      if (!group.some((t) => t.canHost)) {
        const alt = eligible.slice(need - 1).find((t) => t.canHost)
        if (!alt) return null
        group[group.length - 1] = alt
      }
      return { group, cpuSeats: 0 }
    }
    if (anchor.fillAfterMs !== null && age >= anchor.fillAfterMs) {
      // CPU fill: only players who opted in are released into a table with computers, and never a lone human.
      const group = [anchor, ...eligible.filter((t) => t.fillAfterMs !== null)]
      if (group.length < 2 || !group.some((t) => t.canHost)) return null
      return { group, cpuSeats: need - group.length }
    }
    return null
  }

  function formMatch(group, cpuSeats) {
    if (pending.size >= config.queueMaxPending || !deps.canFormRoom()) return false
    const host = chooseHost(group)
    if (!host) return false
    const roomCode = deps.createRoom({ hostPlayerId: host.playerId, gameId: host.gameId, maxSeats: host.maxSeats, tickets: group })
    if (!roomCode) return false
    const t0 = now()
    const match = {
      id: nextMatchId++,
      roomCode,
      host,
      tickets: [host, ...group.filter((t) => t !== host)],
      cpuSeats,
      expiresAt: t0 + config.queueClaimWithinMs,
      claims: new Map(),
    }
    pending.set(match.id, match)
    for (const t of group) {
      removeFromQueue(t)
      t.state = 'matched'
      t.match = match
      match.claims.set(t.playerId, { token: newToken(), used: false, seated: false })
    }
    const seats = match.tickets.map(seatView)
    for (const t of match.tickets) {
      deps.send(t, {
        type: 'matchFound',
        roomCode,
        hostPlayerId: host.playerId,
        claimToken: match.claims.get(t.playerId).token,
        seats,
        cpuSeats,
        claimWithinMs: config.queueClaimWithinMs,
      })
    }
    log(`match: ${match.tickets.length} humans + ${cpuSeats} cpu for ${host.gameId} in room ${roomCode}`)
    return true
  }

  function formMatches() {
    let formed = true
    while (formed) {
      formed = false
      const t0 = now()
      for (const queue of Array.from(queues.values())) {
        for (const anchor of queue) {
          const candidate = buildGroup(queue, anchor, t0)
          if (candidate && formMatch(candidate.group, candidate.cpuSeats)) {
            formed = true
            break
          }
        }
        if (formed) break
      }
    }
  }

  /** Tears a pending match down. `culprits` leave the queue entirely (they did not show up, cancelled, or their
   *  socket closed); everybody else keeps their ORIGINAL enqueuedAt and goes back into the queue. */
  function abortMatch(match, reason, culprits, { penalise = false } = {}) {
    if (!pending.delete(match.id)) return
    deps.abortRoom(match.roomCode)
    const t0 = now()
    for (const t of match.tickets) {
      t.match = null
      if (culprits.has(t)) {
        forget(t)
        if (penalise && config.queueNoShowCooldownMs > 0) cooldownUntil.set(t.ws, t0 + config.queueNoShowCooldownMs)
        deps.send(t, { type: 'matchAborted', reason, roomCode: match.roomCode, requeued: false })
      } else {
        t.state = 'queued'
        t.lastUpdateAt = t0
        const queue = insertSorted(t)
        deps.send(t, { type: 'matchAborted', reason, roomCode: match.roomCode, requeued: true })
        deps.send(t, queuedFrame(t, queue, true))
      }
    }
    log(`match: aborted room ${match.roomCode} (${reason})`)
  }

  function queuedFrame(t, queue, requeued) {
    const frame = {
      type: 'queued',
      ticketId: t.id,
      estimatedWaitMs: null,
      queueSize: queue.length >= 3 ? queue.length : null, // never an exact count below 3: that would identify the other player
    }
    if (requeued) frame.requeued = true
    return frame
  }

  /** Removes a ticket from wherever it is: a queue (silently) or a pending match (which is aborted for the others). */
  function dropTicket(t, { penalise = false } = {}) {
    if (t.state === 'queued') {
      removeFromQueue(t)
      forget(t)
      return
    }
    const match = t.match
    if (!match) {
      forget(t)
      return
    }
    abortMatch(match, match.host === t ? 'host_failed' : 'no_show', new Set([t]), { penalise })
  }

  return {
    /** A 'queue' frame from `ws`. Returns {ok:true, ticket} or {ok:false, code, message[, retryAfterMs]}. */
    enqueue(ws, msg) {
      if (byWs.has(ws)) return bad('already_queued', 'This connection already has a quick-match ticket')
      const cd = cooldownUntil.get(ws)
      if (cd !== undefined && cd > now()) return bad('queue_cooldown', 'You missed a match; wait a moment before searching again', { retryAfterMs: cd - now() })
      if (byWs.size >= config.queueMaxTickets) return bad('queue_full', 'Quick match is busy, try again shortly')
      const parsed = parseQueueFrame(msg, seatRanges)
      if (!parsed.ok) return parsed
      const p = parsed.params
      if (playerIds.has(p.playerId)) return bad('player_in_use', 'That playerId already has a ticket')
      const t0 = now()
      const ticket = {
        id: 't-' + crypto.randomBytes(9).toString('base64url'),
        ws,
        ...p,
        enqueuedAt: t0,
        lastUpdateAt: t0,
        state: 'queued',
        match: null,
      }
      byWs.set(ws, ticket)
      playerIds.add(p.playerId)
      const queue = insertSorted(ticket)
      deps.send(ticket, queuedFrame(ticket, queue, false))
      if (deps.measureRtt) deps.measureRtt(ws)
      formMatches()
      return { ok: true, ticket }
    },

    /** 'queueCancel' (idempotent). A cancel after matchFound counts as not showing up for the others. */
    cancel(ws) {
      const t = byWs.get(ws)
      if (!t) return false
      dropTicket(t)
      formMatches()
      return true
    },

    /** The socket closed (or left): same as cancel, and a closed socket in a pending match aborts it. */
    socketGone(ws) {
      const t = byWs.get(ws)
      if (!t) return false
      dropTicket(t)
      formMatches()
      return true
    },

    hasTicket(ws) {
      return byWs.has(ws)
    },

    /** Validates a 'claim' frame. Every failure is the same {ok:false}: unknown room, wrong player, wrong or reused
     *  token, expired match, or a different socket than the one that holds the ticket. On success the token is spent. */
    validateClaim(ws, msg) {
      const fail = { ok: false }
      const t = byWs.get(ws)
      if (!t || t.state !== 'matched' || !t.match) return fail
      const match = t.match
      if (typeof msg.roomCode !== 'string' || msg.roomCode.toUpperCase() !== match.roomCode) return fail
      if (msg.playerId !== t.playerId) return fail
      if (now() >= match.expiresAt) return fail
      const claim = match.claims.get(t.playerId)
      if (!claim || claim.used || !tokensMatch(claim.token, msg.claimToken)) return fail
      claim.used = true
      return { ok: true, ticket: t, match, isHost: match.host === t }
    },

    /** The relay seated a validated claim in the reserved room. Returns {allSeated, match}. When the last seat is
     *  taken the match stops being pending and its tickets are done: from here on it is an ordinary room. */
    markSeated(ticket) {
      const match = ticket.match
      ticket.state = 'seated'
      match.claims.get(ticket.playerId).seated = true
      const allSeated = match.tickets.every((t) => t.state === 'seated')
      if (allSeated) {
        pending.delete(match.id)
        for (const t of match.tickets) {
          forget(t)
          t.match = null
        }
      }
      return { allSeated, match }
    },

    /** Everything time-driven: no-show expiry, ticket expiry, matching (windows widen, CPU fill ripens), progress frames. */
    tick() {
      const t0 = now()
      for (const match of Array.from(pending.values())) {
        if (t0 < match.expiresAt) continue
        const missing = new Set(match.tickets.filter((t) => t.state !== 'seated'))
        abortMatch(match, missing.has(match.host) ? 'host_failed' : 'no_show', missing, { penalise: true })
      }
      for (const queue of Array.from(queues.values())) {
        for (const t of queue.slice()) {
          if (t0 - t.enqueuedAt >= config.queueTicketMaxAgeMs) {
            removeFromQueue(t)
            forget(t)
            deps.send(t, { type: 'queueExpired' })
          }
        }
      }
      formMatches()
      for (const queue of queues.values()) {
        for (const t of queue) {
          if (t0 - t.lastUpdateAt >= config.queueUpdateEveryMs) {
            t.lastUpdateAt = t0
            const waitedMs = t0 - t.enqueuedAt
            deps.send(t, { type: 'queueUpdate', waitedMs, widened: windowFor(waitedMs) > 0 })
          }
        }
      }
    },

    stats() {
      let queued = 0
      for (const q of queues.values()) queued += q.length
      return { tickets: byWs.size, queued, pendingMatches: pending.size, queues: queues.size }
    },
  }
}

module.exports = { createMatchmaker, parseQueueFrame }
