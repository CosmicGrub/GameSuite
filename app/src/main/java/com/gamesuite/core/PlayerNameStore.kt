package com.gamesuite.core

import android.content.Context

const val MAX_PLAYER_NAME_LENGTH = 16

/**
 * Pure cleaning rule for a typed player name: trim, collapse runs of whitespace to a single
 * space, cap at [MAX_PLAYER_NAME_LENGTH] characters (re-trimmed after the cap), and fall back to
 * [default] when nothing is left.
 */
fun cleanPlayerName(raw: String, default: String): String {
    val collapsed = raw.trim().replace(Regex("\\s+"), " ")
    val capped = collapsed.take(MAX_PLAYER_NAME_LENGTH).trim()
    return if (capped.isEmpty()) default else capped
}

/**
 * Remembers the names players typed for pass-and-play seats (slots 0..3) and the single name
 * used in the Nearby/Online lobbies (host AND joining guest). Display strings only: player ids
 * are never derived from these.
 */
class PlayerNameStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("player_names", Context.MODE_PRIVATE)

    fun name(slot: Int, default: String): String =
        cleanPlayerName(prefs.getString("slot_$slot", "") ?: "", default)

    fun setNames(names: List<String>) {
        val editor = prefs.edit()
        names.forEachIndexed { i, n -> editor.putString("slot_$i", cleanPlayerName(n, "")) }
        editor.apply()
    }

    /** The remembered lobby name, or "" when never set. */
    var hostName: String
        get() = prefs.getString("lobby_name", "") ?: ""
        set(value) {
            prefs.edit().putString("lobby_name", cleanPlayerName(value, "")).apply()
        }

    fun lobbyName(default: String): String = cleanPlayerName(hostName, default)
}
