package com.gamesuite.ui.party

import android.content.Context

/** One score change: [delta] points for player [player] (0-based) during round [round] (1-based). */
data class PartyScoreEntry(val round: Int, val player: Int, val delta: Int)

/**
 * Immutable Score Keeper state. [target] 0 means "no target score". [entries] is chronological, so
 * the last element is what Undo removes.
 */
data class PartyScoreState(
    val playerCount: Int,
    val round: Int = 1,
    val target: Int = 0,
    val entries: List<PartyScoreEntry> = emptyList()
)

/** Pure transitions and queries for [PartyScoreState]. Every function returns a new value. */
object PartyScoreKeeper {
    const val MIN_PLAYERS = 1
    const val MAX_PLAYERS = 12
    const val MAX_ROUND = 99
    const val MAX_TARGET = 999_999
    const val MAX_AMOUNT = 999_999

    fun initial(playerCount: Int, target: Int = 0): PartyScoreState =
        PartyScoreState(
            playerCount = playerCount.coerceIn(MIN_PLAYERS, MAX_PLAYERS),
            target = target.coerceIn(0, MAX_TARGET)
        )

    /** Adds [delta] points to [player] in the current round. Zero or an unknown player is a no-op. */
    fun add(state: PartyScoreState, player: Int, delta: Int): PartyScoreState {
        if (delta == 0 || player !in 0 until state.playerCount) return state
        val clamped = delta.coerceIn(-MAX_AMOUNT, MAX_AMOUNT)
        return state.copy(entries = state.entries + PartyScoreEntry(state.round, player, clamped))
    }

    /** True when the current round has at least one entry (so moving on is meaningful). */
    fun canAdvanceRound(state: PartyScoreState): Boolean =
        state.round < MAX_ROUND && state.entries.any { it.round == state.round }

    fun nextRound(state: PartyScoreState): PartyScoreState =
        if (canAdvanceRound(state)) state.copy(round = state.round + 1) else state

    /** The entry Undo would remove, or null. */
    fun lastEntry(state: PartyScoreState): PartyScoreEntry? = state.entries.lastOrNull()

    /**
     * Removes the last entry. Entries are chronological, so if it belonged to an earlier round the
     * current round is empty and the round counter steps back to it.
     */
    fun undo(state: PartyScoreState): PartyScoreState {
        val last = state.entries.lastOrNull() ?: return state
        return state.copy(entries = state.entries.dropLast(1), round = last.round)
    }

    fun setTarget(state: PartyScoreState, target: Int): PartyScoreState =
        state.copy(target = target.coerceIn(0, MAX_TARGET))

    /** New player count; entries for players that no longer exist are dropped. */
    fun resize(state: PartyScoreState, playerCount: Int): PartyScoreState {
        val n = playerCount.coerceIn(MIN_PLAYERS, MAX_PLAYERS)
        if (n == state.playerCount) return state
        return state.copy(playerCount = n, entries = state.entries.filter { it.player < n })
    }

    /** Clears all scores and rounds; keeps the player count and target. */
    fun reset(state: PartyScoreState): PartyScoreState =
        state.copy(round = 1, entries = emptyList())

    /** Running total per player. */
    fun totals(state: PartyScoreState): List<Int> {
        val t = IntArray(state.playerCount)
        for (e in state.entries) if (e.player in t.indices) t[e.player] += e.delta
        return t.toList()
    }

    /**
     * Per-round points: row r-1 is round r (rounds 1..[PartyScoreState.round]); each cell is the
     * net change for that player in that round, or null if the player had no entry that round.
     */
    fun roundTable(state: PartyScoreState): List<List<Int?>> {
        val rows = state.round.coerceAtLeast(1)
        val sums = Array(rows) { arrayOfNulls<Int>(state.playerCount) }
        for (e in state.entries) {
            if (e.round !in 1..rows || e.player !in 0 until state.playerCount) continue
            val row = sums[e.round - 1]
            row[e.player] = (row[e.player] ?: 0) + e.delta
        }
        return sums.map { it.toList() }
    }

    /** Indices of the highest total. Empty when there are no entries or everyone is level. */
    fun leaders(state: PartyScoreState): Set<Int> {
        if (state.entries.isEmpty()) return emptySet()
        val t = totals(state)
        val max = t.maxOrNull() ?: return emptySet()
        val min = t.minOrNull() ?: return emptySet()
        if (max == min) return emptySet()
        return t.indices.filter { t[it] == max }.toSet()
    }

    /** Players at or above the target total; empty when no target is set. */
    fun targetReached(state: PartyScoreState): Set<Int> {
        if (state.target <= 0) return emptySet()
        val t = totals(state)
        return t.indices.filter { t[it] >= state.target }.toSet()
    }

    /**
     * Parses a keypad entry: digits only, 1..[MAX_AMOUNT]. Null for blank, signs, zero or junk
     * (the sign comes from which button the user presses).
     */
    fun parseAmount(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 6 || !t.all { it in '0'..'9' }) return null
        val v = t.toInt()
        return if (v in 1..MAX_AMOUNT) v else null
    }

    /** Compact text form for SharedPreferences: `v1;count;round;target;p:d:r,p:d:r`. */
    fun encode(state: PartyScoreState): String =
        "v1;${state.playerCount};${state.round};${state.target};" +
            state.entries.joinToString(",") { "${it.player}:${it.delta}:${it.round}" }

    /** Inverse of [encode]; null for anything malformed or out of range. */
    fun decode(raw: String?): PartyScoreState? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(';')
        if (parts.size != 5 || parts[0] != "v1") return null
        val count = parts[1].toIntOrNull() ?: return null
        val round = parts[2].toIntOrNull() ?: return null
        val target = parts[3].toIntOrNull() ?: return null
        if (count !in MIN_PLAYERS..MAX_PLAYERS || round !in 1..MAX_ROUND || target !in 0..MAX_TARGET) return null
        val entries = ArrayList<PartyScoreEntry>()
        if (parts[4].isNotEmpty()) {
            for (chunk in parts[4].split(',')) {
                val f = chunk.split(':')
                if (f.size != 3) return null
                val p = f[0].toIntOrNull() ?: return null
                val d = f[1].toIntOrNull() ?: return null
                val r = f[2].toIntOrNull() ?: return null
                if (p !in 0 until count || d == 0 || r !in 1..round) return null
                entries.add(PartyScoreEntry(r, p, d))
            }
        }
        return PartyScoreState(count, round, target, entries)
    }
}

/** SharedPreferences-backed copy of the Score Keeper game, so a relaunch keeps it. */
class PartyScoreKeeperStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("party_score_keeper", Context.MODE_PRIVATE)

    fun load(defaultCount: Int): PartyScoreState =
        PartyScoreKeeper.decode(prefs.getString("state", null)) ?: PartyScoreKeeper.initial(defaultCount)

    fun save(state: PartyScoreState) {
        prefs.edit().putString("state", PartyScoreKeeper.encode(state)).apply()
    }
}
