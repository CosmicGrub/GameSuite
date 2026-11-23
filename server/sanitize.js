// Input sanitising for the only free-text and the only structured-string fields a client can put into a room:
// the display name and the club avatar string.

const MAX_DISPLAY_NAME_LENGTH = 40 // in code points; a display name is cosmetic, truncate rather than reject
const AVATAR_RE = /^[a-z]{2,12}\|[a-z]{2,12}\|[a-z]{2,12}$/ // "pip|sky|circle": character|color|frame, see the Android Avatar.encode()
const MAX_AVATAR_LENGTH = 40

// Control characters (Cc: NUL, tab, newline, ESC ...) become spaces; format characters (Cf: zero-width space,
// bidi overrides such as U+202E that reverse the text after them, BOM ...) and line/paragraph separators are
// removed outright. Everything else, including emoji and non-Latin scripts, is kept.
const CONTROL_RE = /[\p{Cc}]/gu
const INVISIBLE_RE = /[\p{Cf}\p{Zl}\p{Zp}]/gu

/** A cleaned display name: controls stripped, whitespace collapsed and trimmed, at most 40 code points
 *  (a surrogate pair is never split). Returns `fallback` for anything that is not a string or ends up empty. */
function sanitizeDisplayName(raw, fallback) {
  if (typeof raw !== 'string' || !raw) return fallback
  let s = raw.replace(CONTROL_RE, ' ').replace(INVISIBLE_RE, '').replace(/\s+/gu, ' ').trim()
  if (!s) return fallback
  const points = Array.from(s)
  if (points.length > MAX_DISPLAY_NAME_LENGTH) s = points.slice(0, MAX_DISPLAY_NAME_LENGTH).join('').trim()
  return s || fallback
}

/** The avatar string if it is a well-formed club avatar, else undefined (the client then draws its own from the name). */
function sanitizeAvatar(raw) {
  if (typeof raw !== 'string' || raw.length > MAX_AVATAR_LENGTH) return undefined
  return AVATAR_RE.test(raw) ? raw : undefined
}

module.exports = { sanitizeDisplayName, sanitizeAvatar, MAX_DISPLAY_NAME_LENGTH }
