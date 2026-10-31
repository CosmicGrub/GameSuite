package com.gamesuite.core

import android.content.Context

/** Fewest names a roster needs before the Party Toolkit tools use it. */
const val PARTY_ROSTER_MIN = 2

/** Most names a shared Party Toolkit roster holds. */
const val PARTY_ROSTER_MAX = 12

/**
 * Pure rules for the Party Toolkit's shared player roster: an ordered list of names remembered
 * between launches. A stored roster is either empty (the tools fall back to "Player 1..n") or
 * holds [PARTY_ROSTER_MIN]..[PARTY_ROSTER_MAX] names; while the editor is mid-edit it may briefly
 * hold a single name, which the tools treat as "no roster" (see [effective]).
 *
 * Names are cleaned with [cleanPlayerName]. Duplicates are allowed in the stored list; they are
 * only disambiguated for display and for the tools by [displayNames].
 */
object PartyRosterLogic {
    /** Cleans every name, drops blanks and caps the list at [PARTY_ROSTER_MAX]. */
    fun sanitize(names: List<String>): List<String> =
        names.map { cleanPlayerName(it, "") }.filter { it.isNotEmpty() }.take(PARTY_ROSTER_MAX)

    /** Appends [name] (cleaned). Blank names and a full roster leave the list unchanged. */
    fun add(names: List<String>, name: String): List<String> {
        val cleaned = cleanPlayerName(name, "")
        if (cleaned.isEmpty() || names.size >= PARTY_ROSTER_MAX) return names
        return names + cleaned
    }

    /** Removes the name at [index]; an out-of-range index leaves the list unchanged. */
    fun remove(names: List<String>, index: Int): List<String> {
        if (index !in names.indices) return names
        return names.filterIndexed { i, _ -> i != index }
    }

    /** Renames the entry at [index]. A blank result keeps the old name. */
    fun rename(names: List<String>, index: Int, newName: String): List<String> {
        if (index !in names.indices) return names
        val cleaned = cleanPlayerName(newName, "")
        if (cleaned.isEmpty()) return names
        return names.mapIndexed { i, n -> if (i == index) cleaned else n }
    }

    /** Moves the entry at [index] by [delta] places (negative = earlier), clamped to the list. */
    fun move(names: List<String>, index: Int, delta: Int): List<String> {
        if (index !in names.indices) return names
        val target = (index + delta).coerceIn(0, names.lastIndex)
        if (target == index) return names
        val mutable = names.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        return mutable
    }

    /**
     * Names as the tools show them: a repeated name (compared ignoring case) gets " (2)", " (3)"
     * and so on after its first appearance, so every shown name is unique.
     */
    fun displayNames(names: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        val used = HashSet<String>()
        for (n in names) used.add(n.lowercase())
        return names.map { n ->
            val key = n.lowercase()
            val count = (seen[key] ?: 0) + 1
            seen[key] = count
            if (count == 1) {
                n
            } else {
                var k = count
                var candidate = "$n ($k)"
                while (candidate.lowercase() in used) {
                    k++
                    candidate = "$n ($k)"
                }
                used.add(candidate.lowercase())
                candidate
            }
        }
    }

    /**
     * The names a tool should use: the shown roster when it has at least [PARTY_ROSTER_MIN]
     * names, otherwise "[fallbackPrefix] 1".."[fallbackPrefix] n" ([fallbackCount] clamped to
     * 1..[PARTY_ROSTER_MAX]), which is the toolkit's behaviour before a roster existed.
     */
    fun effective(names: List<String>, fallbackCount: Int, fallbackPrefix: String = "Player"): List<String> {
        if (names.size >= PARTY_ROSTER_MIN) return displayNames(names)
        val n = fallbackCount.coerceIn(1, PARTY_ROSTER_MAX)
        return List(n) { "$fallbackPrefix ${it + 1}" }
    }

    /** True when [names] is big enough for the tools to use it. */
    fun isActive(names: List<String>): Boolean = names.size >= PARTY_ROSTER_MIN

    /** "Ann, Bo, Cy +2": the first [shown] names and how many more there are. Empty list gives "". */
    fun summary(names: List<String>, shown: Int = 3): String {
        if (names.isEmpty()) return ""
        val head = names.take(shown.coerceAtLeast(1)).joinToString(", ")
        val extra = names.size - shown.coerceAtLeast(1)
        return if (extra > 0) "$head +$extra" else head
    }

    /** One name per line. Cleaned names never contain a newline, so this round-trips. */
    fun encode(names: List<String>): String = sanitize(names).joinToString("\n")

    fun decode(raw: String?): List<String> =
        if (raw.isNullOrEmpty()) emptyList() else sanitize(raw.split('\n'))
}

/** SharedPreferences-backed roster, remembered between launches. Display strings only. */
class PartyRosterStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("party_roster", Context.MODE_PRIVATE)

    fun load(): List<String> = PartyRosterLogic.decode(prefs.getString(KEY_NAMES, ""))

    fun save(names: List<String>) {
        prefs.edit().putString(KEY_NAMES, PartyRosterLogic.encode(names)).apply()
    }

    private companion object {
        const val KEY_NAMES = "names"
    }
}
