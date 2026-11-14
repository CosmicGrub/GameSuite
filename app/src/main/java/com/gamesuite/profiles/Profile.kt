package com.gamesuite.profiles

import com.gamesuite.core.ProfileScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ProfileKind { ADULT, KID }

/** The age bands a kid profile can pick. [maxAge] is the top age used to filter games by minAge. */
enum class AgeBand(val id: String, val minAge: Int, val maxAge: Int) {
    AGE_4_6("4-6", 4, 6),
    AGE_7_9("7-9", 7, 9),
    AGE_10_12("10-12", 10, 12);

    companion object {
        fun fromId(id: String?): AgeBand? = values().firstOrNull { it.id == id }
    }
}

/**
 * One person using the app. [avatar] is a core.Avatar.encode() string ("pip|sky|circle") so the picker reuses
 * AvatarBadge. [ageBand] is only meaningful for KID profiles. Stored as JSON in the device-level "profiles" store.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val avatar: String = "pip|sky|circle",
    val kind: ProfileKind = ProfileKind.ADULT,
    val ageBand: AgeBand? = null,
    val createdAt: Long = 0L
) {
    val isKid: Boolean get() = kind == ProfileKind.KID
}

// ---- name rules --------------------------------------------------------------------------------

const val PROFILE_NAME_MAX = 16

sealed interface NameResult {
    data class Ok(val clean: String) : NameResult
    object Empty : NameResult
    object TooLong : NameResult
    object InvalidChars : NameResult
    object LooksLikeLink : NameResult
    object Duplicate : NameResult
}

/**
 * Profile name rules: NFC-normalise, trim and collapse spaces; 1-16 characters (counted in Unicode code points,
 * so a letter outside the BMP counts once); letters in ANY script (with their combining marks), digits, spaces,
 * apostrophes and hyphens only; at least one letter or digit; nothing that looks like a link; not equal (ignoring
 * case) to one of [existingNames]. Emoji, symbols, RTL / bidi overrides, zero-width and control characters,
 * variation selectors and stacked "zalgo" marks are all [NameResult.InvalidChars].
 */
fun validateProfileName(raw: String, existingNames: Collection<String> = emptyList()): NameResult {
    val clean = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
    if (clean.isEmpty()) return NameResult.Empty
    val lower = clean.lowercase()
    val squashed = lower.replace(" ", "")
    if ("http" in squashed || "www" in squashed || "://" in lower) return NameResult.LooksLikeLink
    if (clean.codePointCount(0, clean.length) > PROFILE_NAME_MAX) return NameResult.TooLong
    if (!hasOnlyAllowedNameChars(clean)) return NameResult.InvalidChars
    val norm = { n: String -> java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFC).trim().lowercase() }
    if (existingNames.any { norm(it) == lower }) return NameResult.Duplicate
    return NameResult.Ok(clean)
}

private const val MAX_STACKED_MARKS = 3

private fun isNameMark(cp: Int): Boolean {
    val t = Character.getType(cp)
    return (t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()) &&
        cp !in 0xFE00..0xFE0F && cp !in 0xE0100..0xE01EF // variation selectors are how emoji presentation is forced
}

private fun isNameLetterOrDigit(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
    Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt() -> true
    else -> false
}

internal fun hasOnlyAllowedNameChars(s: String): Boolean {
    var hasBase = false
    var marks = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == ' '.code || cp == '\''.code || cp == 0x2019 /* ’ */ || cp == '-'.code || cp == 0x2010 /* ‐ */ -> marks = 0
            isNameLetterOrDigit(cp) -> { hasBase = true; marks = 0 }
            isNameMark(cp) -> {
                if (!hasBase || ++marks > MAX_STACKED_MARKS) return false
            }
            else -> return false
        }
    }
    return hasBase
}

// ---- pure list logic ---------------------------------------------------------------------------

const val MAX_PROFILES = 6

@Serializable
data class ProfilesState(val profiles: List<Profile>, val activeId: String) {
    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    fun byId(id: String): Profile? = profiles.firstOrNull { it.id == id }

    /** The picker is offered at launch only when there is a real choice. */
    val needsPicker: Boolean get() = profiles.size > 1
}

sealed interface DeleteResult {
    data class Deleted(val state: ProfilesState) : DeleteResult
    object IsDefault : DeleteResult
    object IsActive : DeleteResult
    object LastAdult : DeleteResult
    object NotFound : DeleteResult
}

sealed interface AddResult {
    data class Added(val state: ProfilesState, val profile: Profile) : AddResult
    data class BadName(val reason: NameResult) : AddResult
    object TooMany : AddResult
}

/** Outcome of reading the stored profile list (see [ProfilesLogic.read]). */
sealed interface ProfilesRead {
    object Missing : ProfilesRead
    data class Ok(val state: ProfilesState) : ProfilesRead
    /** Unreadable stored value: [raw] is preserved by the store; [recovered] is the last good copy, when usable. */
    data class Corrupt(val raw: String, val recovered: ProfilesState?) : ProfilesRead
}

object ProfilesLogic {
    /**
     * First run after the update: wraps the existing single user (whose club profile name/avatar may be
     * blank) into the default ADULT profile. The default profile id maps to the pre-existing store names,
     * so nothing else is migrated.
     */
    fun initial(legacyName: String, legacyAvatar: String?, now: Long): ProfilesState {
        val name = (validateProfileName(legacyName) as? NameResult.Ok)?.clean ?: "Player"
        val p = Profile(
            id = ProfileScope.DEFAULT_PROFILE_ID, name = name,
            avatar = legacyAvatar?.takeIf { it.isNotBlank() } ?: "pip|sky|circle",
            kind = ProfileKind.ADULT, createdAt = now
        )
        return ProfilesState(listOf(p), p.id)
    }

    fun add(
        state: ProfilesState, rawName: String, kind: ProfileKind, ageBand: AgeBand?, avatar: String,
        now: Long, newId: String
    ): AddResult {
        if (state.profiles.size >= MAX_PROFILES) return AddResult.TooMany
        val r = validateProfileName(rawName, state.profiles.map { it.name })
        if (r !is NameResult.Ok) return AddResult.BadName(r)
        val band = if (kind == ProfileKind.KID) (ageBand ?: AgeBand.AGE_7_9) else null
        val p = Profile(newId, r.clean, avatar, kind, band, now)
        return AddResult.Added(state.copy(profiles = state.profiles + p), p)
    }

    fun rename(state: ProfilesState, id: String, rawName: String): Pair<ProfilesState, NameResult> {
        val others = state.profiles.filter { it.id != id }.map { it.name }
        val r = validateProfileName(rawName, others)
        if (r !is NameResult.Ok) return state to r
        return state.copy(profiles = state.profiles.map { if (it.id == id) it.copy(name = r.clean) else it }) to r
    }

    /** Deleting is refused for the default profile (it owns the legacy data), the active one, and the last adult. */
    fun delete(state: ProfilesState, id: String): DeleteResult {
        val target = state.byId(id) ?: return DeleteResult.NotFound
        if (id == ProfileScope.DEFAULT_PROFILE_ID) return DeleteResult.IsDefault
        if (id == state.activeId) return DeleteResult.IsActive
        val remaining = state.profiles.filter { it.id != id }
        if (target.kind == ProfileKind.ADULT && remaining.none { it.kind == ProfileKind.ADULT }) return DeleteResult.LastAdult
        return DeleteResult.Deleted(state.copy(profiles = remaining))
    }

    fun switch(state: ProfilesState, id: String): ProfilesState =
        if (state.byId(id) != null) state.copy(activeId = id) else state

    /** Repairs a stored state: drops duplicates, guarantees a valid active id and at least one adult. */
    fun sanitize(state: ProfilesState?, now: Long): ProfilesState {
        if (state == null || state.profiles.isEmpty()) return initial("", null, now)
        val unique = state.profiles.distinctBy { it.id }.filter { ProfileScope.isSafeId(it.id) }
        if (unique.isEmpty()) return initial("", null, now)
        val withAdult = if (unique.none { it.kind == ProfileKind.ADULT })
            listOf(initial("", null, now).profiles.first()) + unique else unique
        val active = if (withAdult.any { it.id == state.activeId }) state.activeId else withAdult.first().id
        return ProfilesState(withAdult, active)
    }

    // Strict on purpose (no coerceInputValues): an unknown enum value must not silently become an ADULT profile.
    // An undecodable value is quarantined and read as "unreadable" instead (see [read]).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: ProfilesState): String = json.encodeToString(ProfilesState.serializer(), state)

    /**
     * Reads the stored value without ever turning a failure into a reset: [ProfilesRead.Missing] only for a
     * blank / never-written value, [ProfilesRead.Corrupt] (raw kept, plus the last good copy when it decodes)
     * for anything else that cannot be decoded.
     */
    fun read(raw: String?, lastGoodRaw: String? = null): ProfilesRead {
        if (raw.isNullOrBlank()) return ProfilesRead.Missing
        val ok = decode(raw)
        if (ok != null) return if (ok.profiles.isEmpty()) ProfilesRead.Missing else ProfilesRead.Ok(ok)
        val recovered = decode(lastGoodRaw)?.takeIf { it.profiles.isNotEmpty() }
        return ProfilesRead.Corrupt(raw, recovered)
    }

    /** A fresh profile id that no profile in [state] uses yet. */
    fun newUniqueId(state: ProfilesState, random: kotlin.random.Random = kotlin.random.Random.Default): String {
        val used = state.profiles.map { it.id }.toSet()
        repeat(50) { val id = newId(random); if (id !in used) return id }
        var n = 0
        while (true) { val id = "k%08x".format(n++); if (id !in used) return id }
    }

    fun decode(raw: String?): ProfilesState? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString(ProfilesState.serializer(), raw) }.getOrNull()

    /** A fresh id safe for file names: "k" + 8 lowercase hex chars. */
    fun newId(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        "k" + (0 until 8).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
}
