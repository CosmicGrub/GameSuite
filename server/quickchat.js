// Quick-chat: fixed phrases and emotes, never free text. A chat frame carries only a phraseId; the relay accepts
// it only if it is in quickchat-allowlist.json, so even a hacked client cannot send words. Chat is relayed with
// the sender's seat stamped from the socket (like 'message'), is never stored, and never travels the game
// 'message' path: it has its own frame type, so it cannot desync or inject into a match.
const allowlist = require('./quickchat-allowlist.json')

const VALID_IDS = new Set([...allowlist.phrases, ...allowlist.emotes])

/** True only for an integer JSON number in the allowlist (not a string, float, boolean, negative, NaN ...). */
function isValidPhraseId(value) {
  return typeof value === 'number' && Number.isInteger(value) && VALID_IDS.has(value)
}

/** Per-sender limiter: at most `minGapMs` between messages, at most `perWindow` in `windowMs`, and the same id
 *  not twice within `sameIdMs`. The numbers match the client's own cooldown (design section 4.4). Rejected
 *  messages do not count, so a dropped message never extends the sender's wait. */
class ChatLimiter {
  constructor(c) {
    this.c = c
    this.times = [] // accepted send times inside the window
    this.lastId = null
    this.lastIdAt = -Infinity
  }

  /** Returns true and records the message if it may be sent now. */
  tryAcquire(phraseId, now) {
    const c = this.c
    this.times = this.times.filter((t) => now - t < c.chatWindowMs)
    const lastAt = this.times.length ? this.times[this.times.length - 1] : -Infinity
    if (now - lastAt < c.chatMinGapMs) return false
    if (this.times.length >= c.chatPerWindow) return false
    if (phraseId === this.lastId && now - this.lastIdAt < c.chatSameIdMs) return false
    this.times.push(now)
    this.lastId = phraseId
    this.lastIdAt = now
    return true
  }
}

module.exports = { VALID_IDS, isValidPhraseId, ChatLimiter }
