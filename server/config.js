// Tunables added in wave 5 (rate limits, lobby TTL, code cooldown, matchmaking, quick-chat). Every one can be
// overridden from the environment; the defaults are deliberately generous so legitimate play, including 60 Hz
// real-time games, never notices them. The older constants (MAX_ROOMS, the ws payload cap, the reconnect grace,
// the idle sweep ...) still live at the top of index.js next to their `*_OVERRIDE` variables.

function envInt(env, name, fallback, min = 0) {
  const raw = env[name]
  if (raw === undefined || raw === '') return fallback
  const n = parseInt(raw, 10)
  return Number.isFinite(n) && n >= min ? n : fallback
}

function envFlag(env, name, fallback = false) {
  const raw = env[name]
  if (raw === undefined || raw === '') return fallback
  return raw === '1' || raw.toLowerCase() === 'true'
}

function loadConfig(env = process.env) {
  // TRUST_PROXY: 0 = ignore X-Forwarded-For (default; the safe choice when the relay faces the internet directly),
  // 1 = the relay sits behind exactly one reverse proxy (Fly, Render, nginx ...) and the LAST entry of
  // X-Forwarded-For, the one that proxy appended, is the client. N = N trusted proxies in a chain.
  const trustRaw = env.TRUST_PROXY
  const trustProxy = trustRaw === undefined || trustRaw === '' ? 0
    : trustRaw.toLowerCase() === 'true' ? 1
    : Math.max(0, parseInt(trustRaw, 10) || 0)

  const codeLengthOverridden = env.ROOM_CODE_LENGTH_OVERRIDE !== undefined && env.ROOM_CODE_LENGTH_OVERRIDE !== ''
  return {
    trustProxy,
    // Loopback addresses are exempt from the per-IP budgets unless this is set: local development and the
    // existing test suites open hundreds of sockets from 127.0.0.1. Behind a same-host reverse proxy set
    // TRUST_PROXY instead, so clients are told apart by X-Forwarded-For and are not loopback.
    rateLimitLoopback: envFlag(env, 'RATE_LIMIT_LOOPBACK'),
    enableTestControl: envFlag(env, 'ENABLE_TEST_CONTROL'),

    // Per-IP budgets.
    joinFailPerIp: envInt(env, 'JOIN_FAIL_PER_IP', 10, 1), // weighted refused joins/peeks per window before the IP is blocked
    joinFailWindowMs: envInt(env, 'JOIN_FAIL_WINDOW_MS', 60 * 1000, 1),
    ipBlockMs: envInt(env, 'IP_BLOCK_MS', 60 * 1000, 1),
    hostPerIpPerMin: envInt(env, 'HOST_PER_IP_PER_MIN', 6, 1),
    maxRoomsPerIp: envInt(env, 'MAX_ROOMS_PER_IP', 30, 1),
    queueFramesPerIpPerMin: envInt(env, 'QUEUE_FRAMES_PER_IP_PER_MIN', 6, 1),

    // Per-socket flood guard. A host that fans out per-recipient redacted state at 60 Hz to 3 guests sends 180
    // frames/s, so the sustained rate is well above that. Frames over the budget are dropped; a "strike" is
    // counted at most once a second; STRIKES strikes inside STRIKE_WINDOW_MS terminate the socket.
    msgRatePerSec: envInt(env, 'MSG_RATE_PER_SEC', 240, 1),
    msgBurst: envInt(env, 'MSG_BURST', 480, 1),
    msgBytesPerSec: envInt(env, 'MSG_BYTES_PER_SEC', 1024 * 1024, 1),
    msgBytesBurst: envInt(env, 'MSG_BYTES_BURST', 2 * 1024 * 1024, 1),
    floodStrikes: envInt(env, 'FLOOD_STRIKES', 3, 1),
    floodStrikeWindowMs: envInt(env, 'FLOOD_STRIKE_WINDOW_MS', 10 * 1000, 1),
    floodStrikeGapMs: envInt(env, 'FLOOD_STRIKE_GAP_MS', 1000, 0),

    // Rooms.
    lobbyTtlMs: envInt(env, 'LOBBY_TTL_MS', 10 * 60 * 1000, 1),
    // A freed room code is not handed out again for this long. Off (0) when ROOM_CODE_LENGTH_OVERRIDE shrinks
    // the code space for identity-test.js, which deliberately forces code reuse; CODE_COOLDOWN_MS wins if set.
    codeCooldownMs: envInt(env, 'CODE_COOLDOWN_MS', codeLengthOverridden ? 0 : 60 * 1000),

    // Quick-chat.
    chatMinGapMs: envInt(env, 'CHAT_MIN_GAP_MS', 1200),
    chatPerWindow: envInt(env, 'CHAT_PER_20S', 5, 1),
    chatWindowMs: envInt(env, 'CHAT_WINDOW_MS', 20 * 1000, 1),
    chatSameIdMs: envInt(env, 'CHAT_SAME_ID_MS', 5000),

    // Matchmaking.
    queueMaxTickets: envInt(env, 'QUEUE_MAX_TICKETS', 2000, 1),
    queueMaxPending: envInt(env, 'QUEUE_MAX_PENDING', 100, 1),
    queueTicketMaxAgeMs: envInt(env, 'QUEUE_TICKET_MAX_AGE_MS', 3 * 60 * 1000, 1),
    queueClaimWithinMs: envInt(env, 'QUEUE_CLAIM_WITHIN_MS', 15 * 1000, 1),
    queueNoShowCooldownMs: envInt(env, 'QUEUE_NOSHOW_COOLDOWN_MS', 30 * 1000),
    queueUpdateEveryMs: envInt(env, 'QUEUE_UPDATE_EVERY_MS', 5 * 1000, 1),
    queueWindow1Ms: envInt(env, 'QUEUE_WINDOW1_MS', 15 * 1000, 0), // under this age: same skill bucket only
    queueWindow2Ms: envInt(env, 'QUEUE_WINDOW2_MS', 30 * 1000, 0), // under this age: +-1 bucket; beyond: any bucket
    queueTickMs: envInt(env, 'QUEUE_TICK_MS', 1000, 10),
  }
}

module.exports = { loadConfig, envInt, envFlag }
