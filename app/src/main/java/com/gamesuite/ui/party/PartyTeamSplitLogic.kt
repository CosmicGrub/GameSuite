package com.gamesuite.ui.party

import kotlin.random.Random

/** Pure team-splitting rules for the Team Splitter tool. */
object PartyTeamSplit {
    const val MIN_TEAMS = 2
    const val MAX_TEAMS = 4

    /** The most teams worth offering for [playerCount] names (never more teams than players). */
    fun maxTeamsFor(playerCount: Int): Int = playerCount.coerceIn(MIN_TEAMS, MAX_TEAMS)

    /**
     * Shuffles [names] with [random] and deals them round-robin into [teamCount] teams
     * (clamped to 2..4 and to the number of names), so team sizes differ by at most one.
     * An empty list gives no teams.
     */
    fun split(names: List<String>, teamCount: Int, random: Random = Random): List<List<String>> {
        if (names.isEmpty()) return emptyList()
        val count = teamCount.coerceIn(MIN_TEAMS, MAX_TEAMS).coerceAtMost(names.size)
        val shuffled = names.shuffled(random)
        val teams = List(count) { mutableListOf<String>() }
        shuffled.forEachIndexed { i, n -> teams[i % count].add(n) }
        return teams
    }

    /** "A", "B", "C", "D"... */
    fun letter(index: Int): String = ('A' + index.coerceIn(0, 25)).toString()

    /** Plain text for the clipboard: one line per team, `Team A: Ann, Bo`. */
    fun asText(teams: List<List<String>>, teamWord: String = "Team"): String =
        teams.withIndex().joinToString("\n") { (i, members) ->
            "$teamWord ${letter(i)}: ${members.joinToString(", ")}"
        }

    /** Largest minus smallest team size (0 or 1 for a fair split). */
    fun sizeSpread(teams: List<List<String>>): Int {
        if (teams.isEmpty()) return 0
        return teams.maxOf { it.size } - teams.minOf { it.size }
    }
}
