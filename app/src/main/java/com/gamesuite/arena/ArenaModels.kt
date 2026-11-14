package com.gamesuite.arena

import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.GameSpeed

/** The three computer levels of the Arena (Easy / Medium / Hard) and the game-side [CpuDifficulty] each means. */
enum class ArenaLevel(val id: String, val difficulty: CpuDifficulty) {
    EASY("easy", CpuDifficulty.EASY),
    MEDIUM("medium", CpuDifficulty.MEDIUM),
    HARD("hard", CpuDifficulty.HARD);

    companion object {
        /** Tolerant parse ("Hard", " hard ", "h"); null when unknown. */
        fun parse(value: String?): ArenaLevel? {
            val v = value?.trim()?.lowercase() ?: return null
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.id == v } ?: entries.firstOrNull { it.id.startsWith(v) }
        }
    }
}

/**
 * The speed control. [gameSpeed] replaces the player's game-speed setting while an arena runs (every bot
 * pause and animation in every game screen multiplies through it); [betweenGamesMs] is the pause that
 * shows each result before the next game starts.
 */
enum class ArenaSpeed(val label: String, val gameSpeed: GameSpeed, val betweenGamesMs: Long) {
    X1("1x", GameSpeed.NORMAL, 3000L),
    X2("2x", GameSpeed.QUICK, 1800L),
    X4("4x", GameSpeed.ARENA_X4, 1000L),
    MAX("Max", GameSpeed.ARENA_MAX, 300L);

    /** Scales a base pause the way the game screens do at this speed. */
    fun scale(baseMs: Long): Long = gameSpeed.scale(baseMs)

    fun next(): ArenaSpeed = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(value: String?): ArenaSpeed? {
            val v = value?.trim() ?: return null
            return entries.firstOrNull { it.name.equals(v, ignoreCase = true) || it.label.equals(v, ignoreCase = true) }
        }
    }
}

/**
 * What a finished game was: a winning seat, a draw, or (cooperative games) the team built the bridge or not.
 * [Stalled] is a game the watchdog ended because nothing changed for too long (see [ArenaWatchdog]); it is
 * counted apart from wins and draws and points at an engine or screen bug rather than at a strength.
 */
sealed interface ArenaOutcome {
    data class Winner(val seat: Int) : ArenaOutcome
    data object Draw : ArenaOutcome
    data class Team(val succeeded: Boolean) : ArenaOutcome
    data object Stalled : ArenaOutcome
}

/** What an adapter reports on each poll of the live game. */
sealed interface ArenaPoll {
    data object Running : ArenaPoll
    data class Over(val outcome: ArenaOutcome) : ArenaPoll
}

/**
 * The tally. Computer A is always the even seats (0, 2) and Computer B the odd ones (1, 3), so the seat colours
 * never change; who MOVES first alternates ([ArenaSeries.firstSeat]). [teamBuilt] / [teamFailed] count the
 * cooperative games (Bridge Builders), where both computers are on the same side.
 */
data class ArenaScoreboard(
    val aWins: Int = 0,
    val bWins: Int = 0,
    val draws: Int = 0,
    val teamBuilt: Int = 0,
    val teamFailed: Int = 0,
    val stalled: Int = 0
) {
    val played: Int get() = aWins + bWins + draws + teamBuilt + teamFailed + stalled

    fun after(outcome: ArenaOutcome): ArenaScoreboard = when (outcome) {
        is ArenaOutcome.Winner -> if (ArenaSeries.sideOf(outcome.seat) == 0) copy(aWins = aWins + 1) else copy(bWins = bWins + 1)
        ArenaOutcome.Draw -> copy(draws = draws + 1)
        is ArenaOutcome.Team -> if (outcome.succeeded) copy(teamBuilt = teamBuilt + 1) else copy(teamFailed = teamFailed + 1)
        ArenaOutcome.Stalled -> copy(stalled = stalled + 1)
    }
}

/** Series settings. [games] is the number of games; 0 (or less) means endless. */
data class ArenaConfig(
    val gameId: String,
    val levelA: ArenaLevel = ArenaLevel.HARD,
    val levelB: ArenaLevel = ArenaLevel.EASY,
    val games: Int = 5,
    /** Round-robin of Easy / Medium / Hard for [gameId] instead of one pairing ([levelA] and [levelB] are then ignored). */
    val tournament: Boolean = false
) {
    val endless: Boolean get() = games <= 0

    /** The level of [seat]: Computer A holds the even seats, Computer B the odd ones. */
    fun levelOfSeat(seat: Int): ArenaLevel = if (ArenaSeries.sideOf(seat) == 0) levelA else levelB

    /** The same series with the two computers' levels exchanged ("Rematch with seats swapped"). */
    fun swapped(): ArenaConfig = copy(levelA = levelB, levelB = levelA)
}

/** Pure series rules (seat swap, completion, game counter). */
object ArenaSeries {
    /** 0 for Computer A (even seats), 1 for Computer B (odd seats). */
    fun sideOf(seat: Int): Int = seat % 2

    /** The seat that moves first in the game with 0-based [gameIndex]: A opens game 1, B game 2, and so on. */
    fun firstSeat(gameIndex: Int): Int = if (gameIndex < 0) 0 else gameIndex % 2

    /** True when no more games are to be played after [played] finished ones. Endless series never complete. */
    fun isComplete(config: ArenaConfig, played: Int): Boolean = !config.endless && played >= config.games

    /** The 1-based number of the game in progress, held at the series length once everything is played. */
    fun currentGameNumber(config: ArenaConfig, played: Int): Int =
        if (config.endless) played + 1 else (played + 1).coerceAtMost(config.games)
}

/** One game the arena can run: the nav route it opens, its seat count and the options that make the computers play. */
data class ArenaGameSpec(
    val gameId: String,
    val route: String,
    val seats: Int = 2,
    val cooperative: Boolean = false,
    val extras: Map<String, String> = emptyMap()
)

/** Pure parsing of the debug deep link (`--es arena_game ... --es arena_a ... --es arena_b ... --ei arena_games N`). */
object ArenaIntent {
    const val EXTRA_GAME = "arena_game"
    const val EXTRA_A = "arena_a"
    const val EXTRA_B = "arena_b"
    const val EXTRA_GAMES = "arena_games"
    const val EXTRA_SPEED = "arena_speed"

    fun parse(
        game: String?,
        a: String?,
        b: String?,
        games: Int?,
        supported: Set<String> = ArenaGames.ids
    ): ArenaConfig? {
        val id = game?.trim()?.lowercase()?.takeIf { it in supported } ?: return null
        return ArenaConfig(
            gameId = id,
            levelA = ArenaLevel.parse(a) ?: ArenaLevel.HARD,
            levelB = ArenaLevel.parse(b) ?: ArenaLevel.EASY,
            games = games ?: 5
        )
    }
}
