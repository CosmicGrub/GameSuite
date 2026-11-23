// Room metadata (wave 5, slice S1): what a host declares when it opens a room, and the view of a room that an
// unseated client may see through 'peek'. Metadata is ADVISORY and host-declared: the relay still never looks at
// game traffic. Its one enforcement use is refusing a seat when the room is full, locked or started, which saves
// the joiner the join-then-bounce round trip through the host.

const GAME_ID_RE = /^[a-z0-9-]{1,32}$/

/** Validates the optional metadata fields of a 'host' frame. A frame without gameId is a legacy host (an old client):
 *  {ok:true, meta:null}. Otherwise gameId must be well-formed, maxSeats is clamped to 1..maxPlayers and minSeats to
 *  1..maxSeats; fields of the wrong type are refused rather than guessed at. */
function parseHostMeta(msg, maxPlayers) {
  const hasGame = msg.gameId !== undefined && msg.gameId !== null
  const hasOther = msg.maxSeats !== undefined || msg.minSeats !== undefined || msg.public !== undefined
  if (!hasGame) {
    if (hasOther) return bad('gameId is required when maxSeats, minSeats or public is given')
    return { ok: true, meta: null }
  }
  if (typeof msg.gameId !== 'string' || !GAME_ID_RE.test(msg.gameId)) return bad('gameId must match [a-z0-9-]{1,32}')
  let maxSeats = maxPlayers
  if (msg.maxSeats !== undefined && msg.maxSeats !== null) {
    if (typeof msg.maxSeats !== 'number' || !Number.isInteger(msg.maxSeats)) return bad('maxSeats must be an integer')
    maxSeats = Math.min(maxPlayers, Math.max(1, msg.maxSeats))
  }
  let minSeats = Math.min(2, maxSeats)
  if (msg.minSeats !== undefined && msg.minSeats !== null) {
    if (typeof msg.minSeats !== 'number' || !Number.isInteger(msg.minSeats)) return bad('minSeats must be an integer')
    minSeats = Math.min(maxSeats, Math.max(1, msg.minSeats))
  }
  if (msg.public !== undefined && typeof msg.public !== 'boolean') return bad('public must be a boolean')
  return { ok: true, meta: { gameId: msg.gameId, minSeats, maxSeats, public: msg.public === true } }
}

function bad(message) {
  return { ok: false, code: 'bad_metadata', message }
}

/** 'started' beats 'locked' beats 'lobby'. */
function roomState(room) {
  if (room.started) return 'started'
  if (room.locked) return 'locked'
  return 'lobby'
}

/** What 'peek' returns: counts, the declared game and the host's display name. Deliberately NO player ids, NO
 *  tokens and NO roster: a peek is open to anybody who can guess a code. */
function peekView(roomCode, room, maxPlayers) {
  const host = room.members.get(room.hostPlayerId)
  return {
    type: 'room',
    roomCode,
    gameId: room.meta ? room.meta.gameId : null,
    seatsTaken: room.members.size,
    minSeats: room.meta ? room.meta.minSeats : 2,
    maxSeats: room.meta ? room.meta.maxSeats : maxPlayers,
    state: roomState(room),
    hostName: host ? host.displayName : null,
  }
}

module.exports = { parseHostMeta, roomState, peekView, GAME_ID_RE }
