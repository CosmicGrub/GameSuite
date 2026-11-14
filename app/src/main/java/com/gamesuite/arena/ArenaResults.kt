package com.gamesuite.arena

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One finished (or stalled) game of a series: how it ended and how long it ran (play time, pauses excluded). */
data class ArenaGameRecord(val outcome: ArenaOutcome, val durationMs: Long)

/** How one level did over a set of games: [games] counts every seat it held in each non-stalled game. */
data class ArenaLevelStat(val level: ArenaLevel, val games: Int, val wins: Int, val draws: Int, val losses: Int) {
    /** Wins over games, 0.0 with no games. */
    val winRate: Double get() = if (games == 0) 0.0 else wins.toDouble() / games
}

/**
 * The numbers of a series, from its games ([records], in order). Pure, so the results screen, the persistent
 * log and the tests all read the same figures.
 */
data class ArenaSeriesSummary(val config: ArenaConfig, val records: List<ArenaGameRecord>, val cooperative: Boolean = false) {
    val board: ArenaScoreboard get() = records.fold(ArenaScoreboard()) { b, r -> b.after(r.outcome) }

    /** Games that were played to an end (a stalled game is not one of them). */
    val completed: List<ArenaGameRecord> get() = records.filter { it.outcome != ArenaOutcome.Stalled }

    val stalled: Int get() = records.size - completed.size

    /** Average length of a completed game in milliseconds (0 with none). */
    val averageGameMs: Long get() = if (completed.isEmpty()) 0L else completed.sumOf { it.durationMs } / completed.size

    val longestGameMs: Long get() = completed.maxOfOrNull { it.durationMs } ?: 0L

    /** Fraction of completed games the two computers' team finished (cooperative games), 0.0 with none. */
    val teamBuiltRate: Double
        get() {
            if (completed.isEmpty()) return 0.0
            return completed.count { (it.outcome as? ArenaOutcome.Team)?.succeeded == true }.toDouble() / completed.size
        }

    /**
     * Wins, draws and losses per LEVEL. Computer A and B each hold one level; when both hold the same level it
     * appears once with both seats' games counted (so its win rate is then about one half by construction).
     * Empty for a cooperative series (nobody beats anybody).
     */
    fun levelStats(): List<ArenaLevelStat> {
        if (cooperative) return emptyList()
        val sides = listOf(0 to config.levelA, 1 to config.levelB)
        return sides.map { it.second }.distinct().map { level ->
            var games = 0
            var wins = 0
            var draws = 0
            for ((side, lv) in sides) {
                if (lv != level) continue
                for (r in completed) {
                    games++
                    when (val o = r.outcome) {
                        is ArenaOutcome.Winner -> if (ArenaSeries.sideOf(o.seat) == side) wins++
                        ArenaOutcome.Draw -> draws++
                        else -> {}
                    }
                }
            }
            ArenaLevelStat(level, games, wins, draws, games - wins - draws)
        }
    }
}

/**
 * Ends a game that makes no progress. Fed once per poll with a fingerprint of the live game state and the
 * play time that passed (not while paused); [tick] answers true once the fingerprint has stayed the same for
 * [limitMs]. Pure.
 */
class ArenaWatchdog(val limitMs: Long) {
    private var last: Int? = null
    private var still = 0L

    /** True when the game has shown no change for [limitMs]. */
    fun tick(fingerprint: Int, dtMs: Long): Boolean {
        if (last == null || last != fingerprint) {
            last = fingerprint
            still = 0L
            return false
        }
        still += dtMs
        return still >= limitMs
    }

    /** How long the state has been unchanged. */
    val stillMs: Long get() = still

    fun reset() {
        last = null
        still = 0L
    }

    companion object {
        /** About twenty seconds without a single change is a stuck game at every speed (the slowest bot thinks for three). */
        const val DEFAULT_LIMIT_MS = 20_000L
    }
}

/** A pairing of the tournament: Computer A plays [levelA], Computer B [levelB]. */
data class ArenaFixture(val levelA: ArenaLevel, val levelB: ArenaLevel)

/** The result of one pairing. */
data class ArenaFixtureResult(val fixture: ArenaFixture, val records: List<ArenaGameRecord>) {
    val board: ArenaScoreboard get() = records.fold(ArenaScoreboard()) { b, r -> b.after(r.outcome) }
}

/** One level's line of the tournament table. Two points for a win, one for a draw. */
data class ArenaStanding(val level: ArenaLevel, val wins: Int, val draws: Int, val losses: Int) {
    val games: Int get() = wins + draws + losses
    val points: Int get() = wins * 2 + draws
    val winRate: Double get() = if (games == 0) 0.0 else wins.toDouble() / games
}

/**
 * The round-robin of Easy, Medium and Hard for ONE game: every pairing once ([gamesPerFixture] games each, the
 * opener alternating inside each series). Pure rules; [ArenaHost] runs the fixtures on the same screen.
 */
data class ArenaTournament(val gameId: String, val gamesPerFixture: Int) {
    val fixtures: List<ArenaFixture> = ArenaLevel.entries.flatMapIndexed { i, a ->
        ArenaLevel.entries.drop(i + 1).map { b -> ArenaFixture(a, b) }
    }

    fun configFor(index: Int): ArenaConfig {
        val f = fixtures[index]
        return ArenaConfig(gameId, f.levelA, f.levelB, gamesPerFixture)
    }

    fun isComplete(finishedFixtures: Int): Boolean = finishedFixtures >= fixtures.size

    companion object {
        /** A tournament needs a definite length: an endless setting plays 5 games per pairing. */
        fun gamesPerFixtureFor(games: Int): Int = if (games <= 0) 5 else games

        /** Per-level totals (stalled games excluded), best first (points, then wins, then the harder level). */
        fun standings(results: List<ArenaFixtureResult>): List<ArenaStanding> {
            val tally = ArenaLevel.entries.associateWith { intArrayOf(0, 0, 0) } // wins, draws, losses
            for (r in results) {
                val b = r.board
                tally.getValue(r.fixture.levelA).let { it[0] += b.aWins; it[1] += b.draws; it[2] += b.bWins }
                tally.getValue(r.fixture.levelB).let { it[0] += b.bWins; it[1] += b.draws; it[2] += b.aWins }
            }
            return tally.map { (level, t) -> ArenaStanding(level, t[0], t[1], t[2]) }
                .sortedWith(
                    compareByDescending<ArenaStanding> { it.points }
                        .thenByDescending { it.wins }
                        .thenByDescending { it.level.ordinal }
                )
        }

        /** [row]'s record against [col] as (wins, draws, losses) from [row]'s side; null when they have not met. */
        fun headToHead(results: List<ArenaFixtureResult>, row: ArenaLevel, col: ArenaLevel): Triple<Int, Int, Int>? {
            val r = results.firstOrNull {
                (it.fixture.levelA == row && it.fixture.levelB == col) || (it.fixture.levelA == col && it.fixture.levelB == row)
            } ?: return null
            val b = r.board
            return if (r.fixture.levelA == row) Triple(b.aWins, b.draws, b.bWins) else Triple(b.bWins, b.draws, b.aWins)
        }
    }
}

/** One finished series as the persistent log keeps it (plain values only, so it survives app updates). */
@Serializable
data class ArenaSeriesRecord(
    val gameId: String,
    val levelA: String,
    val levelB: String,
    val games: Int,
    val aWins: Int = 0,
    val bWins: Int = 0,
    val draws: Int = 0,
    val teamBuilt: Int = 0,
    val teamFailed: Int = 0,
    val stalled: Int = 0,
    val averageGameMs: Long = 0L,
    val tournament: Boolean = false,
    val finishedAtMs: Long = 0L
)

/**
 * The persistent list of finished series, newest first, capped at [MAX]. Pure encode / decode; the Android side
 * only stores the text. A corrupt or unknown text decodes to an empty log, never a crash.
 */
object ArenaResultsLog {
    const val MAX = 30
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ArenaSeriesRecord.serializer())

    fun add(log: List<ArenaSeriesRecord>, record: ArenaSeriesRecord): List<ArenaSeriesRecord> = (listOf(record) + log).take(MAX)

    fun encode(log: List<ArenaSeriesRecord>): String = json.encodeToString(serializer, log.take(MAX))

    fun decode(text: String?): List<ArenaSeriesRecord> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text).filter { it.games >= 0 }.take(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordOf(summary: ArenaSeriesSummary, tournament: Boolean, nowMs: Long): ArenaSeriesRecord {
        val b = summary.board
        return ArenaSeriesRecord(
            gameId = summary.config.gameId, levelA = summary.config.levelA.id, levelB = summary.config.levelB.id,
            games = summary.records.size, aWins = b.aWins, bWins = b.bWins, draws = b.draws,
            teamBuilt = b.teamBuilt, teamFailed = b.teamFailed, stalled = b.stalled,
            averageGameMs = summary.averageGameMs, tournament = tournament, finishedAtMs = nowMs
        )
    }
}
