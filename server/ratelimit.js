// Rate limiting for the relay: a per-socket flood guard, per-IP budgets (join failures, hosting, queueing) and
// client-IP resolution. Pure logic with an explicit `now` argument everywhere, so it is unit-testable and the
// relay can drive it from the injectable clock (clock.js).
const crypto = require('crypto')

// ---------------------------------------------------------------------------------------------
// Client IP
// ---------------------------------------------------------------------------------------------

const IP_SHAPE = /^[0-9a-fA-F:.]{2,45}$/

function normalizeIp(raw) {
  if (typeof raw !== 'string') return null
  let ip = raw.trim().toLowerCase()
  if (ip.startsWith('::ffff:') && ip.includes('.')) ip = ip.slice(7) // IPv4-mapped IPv6
  return IP_SHAPE.test(ip) ? ip : null
}

function isLoopback(ip) {
  return ip === '::1' || ip === '127.0.0.1' || (typeof ip === 'string' && ip.startsWith('127.'))
}

/** The client address used for per-IP budgets. With trustProxy = 0 the X-Forwarded-For header is IGNORED (a client
 *  can write anything in it). With trustProxy = N the relay is behind N proxies in a chain, each appending the
 *  address it saw, so the client is the Nth entry from the END; entries further left were supplied by the client
 *  and are never trusted. A malformed or too-short header falls back to the socket address. */
function resolveClientIp(req, trustProxy) {
  const socketIp = normalizeIp(req.socket && req.socket.remoteAddress) || 'unknown'
  if (!trustProxy || trustProxy < 1) return socketIp
  const header = req.headers && req.headers['x-forwarded-for']
  const text = Array.isArray(header) ? header.join(',') : header
  if (typeof text !== 'string') return socketIp
  const parts = text.split(',').map((p) => p.trim()).filter(Boolean)
  const index = parts.length - trustProxy
  if (index < 0) return socketIp
  return normalizeIp(parts[index]) || socketIp
}

/** First 6 hex chars of a salted hash, the only form of an IP that ever reaches a log line. */
const LOG_SALT = crypto.randomBytes(8).toString('hex') // per process: log lines cannot be correlated across restarts
function hashIp(ip) {
  return crypto.createHash('sha256').update(LOG_SALT + String(ip)).digest('hex').slice(0, 6)
}

// ---------------------------------------------------------------------------------------------
// Per-socket flood guard
// ---------------------------------------------------------------------------------------------

/** Two token buckets (frames and bytes) per socket, plus strikes. check() returns:
 *    {action:'ok'}                       frame is within budget
 *    {action:'drop', notify}             over budget: drop the frame; `notify` is true on the first drop of a strike
 *    {action:'terminate'}                the strike limit inside the strike window was reached */
class FloodGuard {
  constructor(c) {
    this.c = c
    this.frames = c.msgBurst
    this.bytes = c.msgBytesBurst
    this.last = null
    this.lastStrikeAt = -Infinity
    this.strikes = []
  }

  check(byteLength, now) {
    const c = this.c
    if (this.last !== null) {
      const elapsedSec = Math.max(0, now - this.last) / 1000
      this.frames = Math.min(c.msgBurst, this.frames + elapsedSec * c.msgRatePerSec)
      this.bytes = Math.min(c.msgBytesBurst, this.bytes + elapsedSec * c.msgBytesPerSec)
    }
    this.last = now
    if (this.frames >= 1 && this.bytes >= byteLength) {
      this.frames -= 1
      this.bytes -= byteLength
      return { action: 'ok' }
    }
    let notify = false
    if (now - this.lastStrikeAt >= c.floodStrikeGapMs) {
      this.lastStrikeAt = now
      this.strikes.push(now)
      notify = true
    }
    this.strikes = this.strikes.filter((t) => now - t < c.floodStrikeWindowMs)
    if (this.strikes.length >= c.floodStrikes) return { action: 'terminate' }
    return { action: 'drop', notify }
  }
}

// ---------------------------------------------------------------------------------------------
// Per-IP budgets
// ---------------------------------------------------------------------------------------------

class IpLimiter {
  constructor(c) {
    this.c = c
    this.failures = new Map() // ip -> [{t, w}]
    this.blocked = new Map() // ip -> blockedUntil
    this.hostHits = new Map() // ip -> [t]
    this.queueHits = new Map() // ip -> [t]
  }

  exempt(ip) {
    return !this.c.rateLimitLoopback && isLoopback(ip)
  }

  /** Milliseconds this IP stays blocked (0 = not blocked). */
  blockedFor(ip, now) {
    if (this.exempt(ip)) return 0
    const until = this.blocked.get(ip)
    if (until === undefined) return 0
    if (now >= until) {
      this.blocked.delete(ip)
      return 0
    }
    return until - now
  }

  /** Counts a refused join/peek/claim (weight 1), a cheaper refusal (weight < 1) or a successful peek (0.1).
   *  Returns {blocked, retryAfterMs}; blocked becomes true on the call that exhausts the budget. */
  recordFailure(ip, now, weight = 1) {
    if (this.exempt(ip)) return { blocked: false, retryAfterMs: 0 }
    const hits = (this.failures.get(ip) || []).filter((h) => now - h.t < this.c.joinFailWindowMs)
    hits.push({ t: now, w: weight })
    const total = hits.reduce((sum, h) => sum + h.w, 0)
    if (total >= this.c.joinFailPerIp - 1e-9) {
      this.failures.delete(ip)
      this.blocked.set(ip, now + this.c.ipBlockMs)
      return { blocked: true, retryAfterMs: this.c.ipBlockMs }
    }
    this.failures.set(ip, hits)
    return { blocked: false, retryAfterMs: 0 }
  }

  allowHost(ip, now) {
    return this._windowAllow(this.hostHits, ip, now, this.c.hostPerIpPerMin)
  }

  allowQueue(ip, now) {
    return this._windowAllow(this.queueHits, ip, now, this.c.queueFramesPerIpPerMin)
  }

  _windowAllow(map, ip, now, limit) {
    if (this.exempt(ip)) return true
    const hits = (map.get(ip) || []).filter((t) => now - t < 60 * 1000)
    if (hits.length >= limit) {
      map.set(ip, hits)
      return false
    }
    hits.push(now)
    map.set(ip, hits)
    return true
  }

  /** Drops expired state so a long-running relay does not remember every IP that ever connected. */
  sweep(now) {
    for (const [ip, until] of this.blocked) if (now >= until) this.blocked.delete(ip)
    for (const [ip, hits] of this.failures) {
      if (!hits.some((h) => now - h.t < this.c.joinFailWindowMs)) this.failures.delete(ip)
    }
    for (const map of [this.hostHits, this.queueHits]) {
      for (const [ip, hits] of map) if (!hits.some((t) => now - t < 60 * 1000)) map.delete(ip)
    }
  }
}

module.exports = { FloodGuard, IpLimiter, resolveClientIp, normalizeIp, isLoopback, hashIp }
