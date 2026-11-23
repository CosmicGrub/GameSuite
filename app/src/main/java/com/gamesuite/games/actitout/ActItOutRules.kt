package com.gamesuite.games.actitout

import com.gamesuite.core.cleanPlayerName
import kotlin.random.Random

/** How the players are grouped: two teams that take turns, or everybody for themselves. */
enum class ActItOutFormat(val id: String) {
    TEAMS("teams"),
    FREE_FOR_ALL("ffa");

    companion object {
        fun fromId(id: String?): ActItOutFormat = values().firstOrNull { it.id == id } ?: TEAMS
    }
}

/**
 * Which cards a side (a team, or a lone player in free-for-all) is dealt. The band is chosen PER SIDE so a
 * team of little ones and a team of grown-ups can play the same game.
 *
 *  - [LITTLE]: difficulty 1 only. Every difficulty-1 card has its own picture, so a pre-reader plays from the picture.
 *  - [EVERYONE]: difficulties 1 and 2.
 *  - [GROWN_UPS]: difficulties 2 and 3 (no picture-only cards).
 */
enum class ActItOutBand(val id: String, val difficulties: Set<Int>) {
    LITTLE("little", setOf(1)),
    EVERYONE("everyone", setOf(1, 2)),
    GROWN_UPS("grownups", setOf(2, 3));

    fun allows(difficulty: Int): Boolean = difficulty in difficulties

    companion object {
        fun fromId(id: String?): ActItOutBand = values().firstOrNull { it.id == id } ?: EVERYONE
        fun isKnown(id: String?): Boolean = values().any { it.id == id }
    }
}

/** The numbers of Act It Out in one place. */
object ActItOutRules {
    const val MIN_PLAYERS = 3
    const val MAX_PLAYERS = 12
    const val TEAM_COUNT = 2

    /** Skips allowed to the actor in one turn. */
    const val SKIP_LIMIT = 3

    /** A card must have been visible this long before Correct counts for it. */
    const val MIN_CARD_VISIBLE_MS = 300L

    /** Extra points per correct card for a side that has the little kids bonus. */
    const val BONUS_POINTS = 1

    /** The buzzer (tick, tick, ... buzz) covers the last this-many seconds of a turn. */
    const val BUZZER_SECONDS = 5

    const val MIN_ROUNDS = 1
    const val MAX_ROUNDS = 5
    const val DEFAULT_ROUNDS = 3

    const val DEFAULT_SECONDS = 60
    val TIME_CHOICES: List<Int> = listOf(30, 60, 90)

    /** The most characters a player or team name keeps. */
    const val MAX_NAME_LENGTH = 16

    /** Points one correct card is worth to a side. */
    fun pointsFor(bonus: Boolean): Int = 1 + if (bonus) BONUS_POINTS else 0

    /**
     * The "seconds left" marks a screen reader hears about (and never every second): 60, 30, 10 and 5, but only
     * the ones that are less than the round length (a 30 second turn says 10 and 5).
     */
    fun milestones(roundSeconds: Int): List<Int> = listOf(60, 30, 10, 5).filter { it < roundSeconds }

    /** The milestone (seconds left) crossed when the clock goes from [fromMs] to [toMs] remaining, or null. */
    fun milestoneCrossed(roundSeconds: Int, fromMs: Long, toMs: Long): Int? {
        if (toMs >= fromMs) return null
        // The smallest milestone the clock just passed: fromMs > m*1000 >= toMs.
        return milestones(roundSeconds).filter { it * 1000L < fromMs && it * 1000L >= toMs }.minOrNull()
    }

    /** Whole seconds left, rounded UP (so "0" only shows when the time is really out). */
    fun secondsLeft(remainingMs: Long): Int = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L).toInt()
}

/** Team building. Pure and index based, so two people with the same name never get confused. */
object ActItOutTeams {
    /**
     * Splits players 0 until [playerCount] into [ActItOutRules.TEAM_COUNT] teams whose sizes differ by at most one.
     * The deal is shuffled with [random], and when the number is odd the bigger team is picked at random too, so
     * neither team is favoured. Each team's list is in ascending seat order (a steady acting order).
     */
    fun autoSplit(playerCount: Int, random: Random): List<List<Int>> {
        val shuffled = (0 until playerCount).toList().shuffled(random)
        val a = mutableListOf<Int>()
        val b = mutableListOf<Int>()
        val bigFirst = random.nextBoolean()
        shuffled.forEachIndexed { i, p ->
            val toFirst = if (bigFirst) i % 2 == 0 else i % 2 == 1
            if (toFirst) a.add(p) else b.add(p)
        }
        return listOf(a.sorted(), b.sorted())
    }

    /** Free-for-all: every player is a side of one. */
    fun individuals(playerCount: Int): List<List<Int>> = List(playerCount) { listOf(it) }

    /** "A", "B" ... */
    fun letter(index: Int): String = ('A' + index.coerceIn(0, 25)).toString()

    /** Largest minus smallest side size (0 or 1 for a fair split). */
    fun sizeSpread(sides: List<List<Int>>): Int =
        if (sides.isEmpty()) 0 else sides.maxOf { it.size } - sides.minOf { it.size }
}

/** One side of the match: a team, or one player in free-for-all. */
data class ActItOutSide(
    /** A name the players typed; "" means "use the default" (Team A / the player's own name). */
    val name: String,
    /** Seats on this side, in acting order. */
    val members: List<Int>,
    val band: ActItOutBand,
    /** The little kids bonus: each correct card is worth +1 extra. */
    val bonus: Boolean
)

/** Everything fixed when a match starts. A snapshot stores exactly this, and [validate] re-checks it. */
data class ActItOutConfig(
    val playerIds: List<String>,
    val playerNames: List<String>,
    val format: ActItOutFormat,
    val sides: List<ActItOutSide>,
    val roundSeconds: Int,
    val rounds: Int,
    /** True: the actor holds a pad to start; false: one tap does it. */
    val holdToReveal: Boolean,
    /** The tick-tick-buzz in the last [ActItOutRules.BUZZER_SECONDS] seconds. */
    val buzzer: Boolean
) {
    /** Null when the config is playable, else a short reason (used by tests and by snapshot restore). */
    fun validate(): String? {
        val n = playerNames.size
        if (n !in ActItOutRules.MIN_PLAYERS..ActItOutRules.MAX_PLAYERS) return "player count"
        if (playerIds.size != n || playerIds.toSet().size != n) return "player ids"
        if (playerNames.any { it.isBlank() }) return "blank name"
        val want = if (format == ActItOutFormat.TEAMS) ActItOutRules.TEAM_COUNT else n
        if (sides.size != want) return "side count"
        val seen = BooleanArray(n)
        for (s in sides) {
            if (s.members.isEmpty()) return "empty side"
            for (m in s.members) {
                if (m !in 0 until n || seen[m]) return "member"
                seen[m] = true
            }
        }
        if (seen.any { !it }) return "unassigned player"
        if (format == ActItOutFormat.FREE_FOR_ALL && sides.any { it.members.size != 1 }) return "ffa side size"
        if (roundSeconds !in ActItOutRules.TIME_CHOICES) return "round time"
        if (rounds !in ActItOutRules.MIN_ROUNDS..ActItOutRules.MAX_ROUNDS) return "rounds"
        return null
    }

    /** Display name of a side: its typed name, else "Team A"-style via [teamWord], else the lone player's name. */
    fun sideName(side: Int, teamWord: String): String {
        val s = sides.getOrNull(side) ?: return ""
        if (s.name.isNotBlank()) return s.name
        return if (format == ActItOutFormat.TEAMS) "$teamWord ${ActItOutTeams.letter(side)}" else playerNames[s.members.first()]
    }
}

/**
 * What the Hub launch sets before the setup screen opens. Every value comes from `LaunchOptions.extras`
 * (strings), is range-checked, and falls back to a safe default: a stale saved choice cannot reach the game.
 */
data class ActItOutDefaults(
    val format: ActItOutFormat = ActItOutFormat.TEAMS,
    val roundSeconds: Int = ActItOutRules.DEFAULT_SECONDS,
    val rounds: Int = ActItOutRules.DEFAULT_ROUNDS,
    val band: ActItOutBand = ActItOutBand.EVERYONE,
    /** Offer the per-side little kids bonus switches in the setup. */
    val handicap: Boolean = false,
    val buzzer: Boolean = true,
    val holdToReveal: Boolean = true
) {
    companion object {
        fun fromExtras(extras: Map<String, String>): ActItOutDefaults {
            val time = extras["time"]?.toIntOrNull()?.takeIf { it in ActItOutRules.TIME_CHOICES } ?: ActItOutRules.DEFAULT_SECONDS
            val rounds = (extras["rounds"]?.toIntOrNull() ?: ActItOutRules.DEFAULT_ROUNDS)
                .coerceIn(ActItOutRules.MIN_ROUNDS, ActItOutRules.MAX_ROUNDS)
            return ActItOutDefaults(
                format = ActItOutFormat.fromId(extras["format"]),
                roundSeconds = time,
                rounds = rounds,
                band = ActItOutBand.fromId(extras["band"]),
                handicap = extras["handicap"] == "true",
                buzzer = extras["buzzer"] != "false",
                holdToReveal = extras["hold"] != "false"
            )
        }
    }
}

/**
 * The editable setup before a match: player names, which team each player is on, a card band and the little
 * kids bonus per side, round time, rounds. The screen reads and edits it; [build] makes the fixed [ActItOutConfig].
 * Plain mutable class (no Compose): the owner bumps its own version counter after every edit.
 */
class ActItOutSetup(
    private val playerIds: List<String>,
    names: List<String>,
    private val random: Random,
    private val defaults: ActItOutDefaults = ActItOutDefaults(),
    /** The name given to a player whose name is blank (0-based seat); the screen passes a localized one. */
    private val defaultName: (Int) -> String = { "Player ${it + 1}" }
) {
    val playerCount: Int = names.size
    val names: MutableList<String> = names.mapIndexed { i, n -> cleanPlayerName(n, defaultName(i)) }.toMutableList()

    var format: ActItOutFormat = defaults.format
        private set

    /** 0 or 1 per player (Teams format only). */
    val teamOf: IntArray = IntArray(playerCount)
    val teamNames: Array<String> = arrayOf("", "")

    /** Band and bonus of every SIDE: 2 entries for teams, one per player in free-for-all. */
    private var bands: MutableList<ActItOutBand> = MutableList(sideCount()) { defaults.band }
    private var bonuses: MutableList<Boolean> = MutableList(sideCount()) { defaults.band == ActItOutBand.LITTLE && defaults.handicap }

    var roundSeconds: Int = defaults.roundSeconds
    var rounds: Int = defaults.rounds
    var holdToReveal: Boolean = defaults.holdToReveal
    var buzzer: Boolean = defaults.buzzer

    /** Whether the little kids bonus switches are offered at all (the Hub's "Little kids bonus" option). */
    val handicapOffered: Boolean get() = defaults.handicap

    init {
        reshuffleTeams()
    }

    fun sideCount(): Int = if (format == ActItOutFormat.TEAMS) ActItOutRules.TEAM_COUNT else playerCount

    fun band(side: Int): ActItOutBand = bands.getOrElse(side) { defaults.band }
    fun bonus(side: Int): Boolean = bonuses.getOrElse(side) { false }

    fun setBand(side: Int, band: ActItOutBand) {
        if (side !in bands.indices) return
        bands[side] = band
        // Picking "Little kids" on a side is the natural moment to offer the bonus; it stays off until switched on.
        if (band != ActItOutBand.LITTLE && !handicapOffered) bonuses[side] = false
    }

    fun setBonus(side: Int, on: Boolean) {
        if (side in bonuses.indices) bonuses[side] = on
    }

    fun setFormat(newFormat: ActItOutFormat) {
        if (newFormat == format) return
        format = newFormat
        bands = MutableList(sideCount()) { defaults.band }
        bonuses = MutableList(sideCount()) { defaults.band == ActItOutBand.LITTLE && defaults.handicap }
    }

    fun rename(player: Int, name: String) {
        if (player in names.indices) names[player] = cleanPlayerName(name, defaultName(player))
    }

    fun renameTeam(team: Int, name: String) {
        if (team in 0..1) teamNames[team] = cleanPlayerName(name, "")
    }

    /** Deals every player to a team afresh with [ActItOutTeams.autoSplit]. */
    fun reshuffleTeams() {
        val split = ActItOutTeams.autoSplit(playerCount, random)
        split.forEachIndexed { team, members -> members.forEach { teamOf[it] = team } }
    }

    /** Moves [player] to the other team. Refuses when that would leave a team empty. Returns true when it moved. */
    fun togglePlayerTeam(player: Int): Boolean {
        if (player !in 0 until playerCount) return false
        val from = teamOf[player]
        if (teamOf.count { it == from } <= 1) return false
        teamOf[player] = 1 - from
        return true
    }

    fun teamSizes(): List<Int> = listOf(teamOf.count { it == 0 }, teamOf.count { it == 1 })

    fun canStart(): Boolean = build().validate() == null

    fun build(): ActItOutConfig {
        val sides: List<ActItOutSide> =
            if (format == ActItOutFormat.TEAMS) {
                (0..1).map { t ->
                    ActItOutSide(
                        name = teamNames[t],
                        members = (0 until playerCount).filter { teamOf[it] == t },
                        band = band(t),
                        bonus = bonus(t)
                    )
                }
            } else {
                (0 until playerCount).map { p ->
                    ActItOutSide(name = "", members = listOf(p), band = band(p), bonus = bonus(p))
                }
            }
        return ActItOutConfig(
            playerIds = playerIds,
            playerNames = names.toList(),
            format = format,
            sides = sides,
            roundSeconds = roundSeconds,
            rounds = rounds,
            holdToReveal = holdToReveal,
            buzzer = buzzer
        )
    }

    companion object {
        /** A setup that repeats a finished match's choices (for "Play again"), teams as they were. */
        fun from(
            config: ActItOutConfig,
            random: Random,
            defaults: ActItOutDefaults = ActItOutDefaults(),
            defaultName: (Int) -> String = { "Player ${it + 1}" }
        ): ActItOutSetup {
            val s = ActItOutSetup(config.playerIds, config.playerNames, random, defaults.copy(format = config.format), defaultName)
            if (config.format == ActItOutFormat.TEAMS) {
                config.sides.forEachIndexed { t, side ->
                    side.members.forEach { s.teamOf[it] = t }
                    s.teamNames[t] = side.name
                }
            }
            config.sides.forEachIndexed { i, side ->
                s.setBand(i, side.band)
                s.setBonus(i, side.bonus)
            }
            s.roundSeconds = config.roundSeconds
            s.rounds = config.rounds
            s.holdToReveal = config.holdToReveal
            s.buzzer = config.buzzer
            return s
        }
    }
}
