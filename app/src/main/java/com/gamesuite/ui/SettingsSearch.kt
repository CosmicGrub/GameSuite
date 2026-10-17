package com.gamesuite.ui

/**
 * Pure filter behind the Settings search field. A blank query matches everything; otherwise a row
 * matches when the trimmed query appears (case-insensitive) in any of its [texts] (label,
 * description, section title, ...).
 */
fun settingMatches(query: String, vararg texts: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return texts.any { it.contains(q, ignoreCase = true) }
}
