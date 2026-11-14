// Injectable time source for everything in the relay that has a deadline measured in seconds or minutes
// (rate-limit windows, lobby TTL, code cooldown, queue windows, claim expiry, chat cooldowns).
//
// Production: now() is just Date.now(). Tests: a relay started with ENABLE_TEST_CONTROL=1 accepts a
// {"type":"__test","op":"advanceClock","ms":N} frame (see index.js) which calls advance(), so a test can
// "wait" 30 seconds in one frame instead of in 30 real seconds. Modules that are tested in-process take a
// `now` function in their constructor instead and never touch this singleton.
//
// Not covered by the clock on purpose: the reconnect grace timer (a real setTimeout, already overridable with
// RECONNECT_GRACE_MS_OVERRIDE) and the ws heartbeat.
let offsetMs = 0

module.exports = {
  now() {
    return Date.now() + offsetMs
  },
  advance(ms) {
    if (typeof ms === 'number' && Number.isFinite(ms) && ms > 0) offsetMs += ms
    return this.now()
  },
}
