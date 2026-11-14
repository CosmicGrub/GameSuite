package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty

/**
 * Resolves a finished match to THIS device's own win/loss/draw. Pure so every play mode can be
 * unit-tested without a [GameSessionManager].
 *
 * The rules, in order:
 *  - aborted, a spectator, nothing scored, or the local player absent from the scores -> null;
 *  - the local player (or a teammate) is flagged a winner -> [LocalOutcome.WIN];
 *  - an opposing scored player is flagged a winner -> [LocalOutcome.LOSS];
 *  - NOBODY is flagged a winner:
 *      - only the local player was scored (a solo puzzle that solved nothing, a vs-CPU game that
 *        reports just the human's row, e.g. Air Hockey vs the bot) -> [LocalOutcome.LOSS]; this was
 *        the bug: such a session was reported as a draw;
 *      - several players were scored in a game that can genuinely tie ([GameCategory.BOARD],
 *        [GameCategory.CARD], [GameCategory.OTHER]) -> [LocalOutcome.DRAW];
 *      - several players were scored in a puzzle / word / arcade / utility game, where every seat
 *        shares one board or tally (pass-and-play Word Search) and "nobody won" means the board was
 *        not beaten -> [LocalOutcome.LOSS].
 *
 * Known limitation: a game that flags several equal-score players as winners (Word Tiles ties,
 * Dominoes ties, the shared Word Search tally) still reports WIN for each of them; the scores alone
 * cannot tell a tie from a co-operative win.
 */
fun deriveLocalOutcome(
    players: List<PlayerInfo>,
    localPlayerIndex: Int,
    result: GameResult,
    category: GameCategory = GameCategory.BOARD
): LocalOutcome? {
    if (result.wasAborted) return null
    val local = players.getOrNull(localPlayerIndex) ?: return null
    if (result.scores.isEmpty()) return null
    val mine = result.scores.firstOrNull { it.playerId == local.playerId } ?: return null

    fun isTeammate(playerId: String): Boolean =
        local.teamId >= 0 && players.firstOrNull { it.playerId == playerId }?.teamId == local.teamId

    val others = result.scores.filter { it.playerId != local.playerId }
    val opposing = others.filter { !isTeammate(it.playerId) }

    return when {
        mine.isWinner -> LocalOutcome.WIN
        others.any { it.isWinner && isTeammate(it.playerId) } -> LocalOutcome.WIN
        opposing.any { it.isWinner } -> LocalOutcome.LOSS
        opposing.isEmpty() -> LocalOutcome.LOSS
        category in SHARED_BOARD_CATEGORIES -> LocalOutcome.LOSS
        else -> LocalOutcome.DRAW
    }
}

/** Categories whose seats share one board / tally, so "nobody won" is a failure, not a tie. */
private val SHARED_BOARD_CATEGORIES = setOf(
    GameCategory.PUZZLE,
    GameCategory.WORD,
    GameCategory.ARCADE,
    GameCategory.UTILITY
)

/**
 * Extra facts about a finished match that [MatchOutcome] does not carry, published by
 * [GameSessionManager] next to it (see [GameSessionManager.lastMatchDetails]) for the stats and
 * achievements layers.
 */
data class MatchDetails(
    val mode: PlayMode,
    /** True when at least one opponent seat is a bot. */
    val hasBots: Boolean,
    /** The per-launch CPU difficulty override, or null when the app-wide default applied. */
    val cpuDifficulty: CpuDifficulty?,
    /** True for a Daily puzzle (the nav route ends in "-daily"). */
    val daily: Boolean,
    val humanCount: Int
) {
    /** Playing against the computer (a bot opponent in a one-device game). */
    val vsCpu: Boolean get() = hasBots && mode == PlayMode.SINGLE_PLAYER_VS_BOT

    /** Fills in the app-wide default difficulty when the launch carried no override. */
    fun withDefaultDifficulty(default: CpuDifficulty): MatchDetails =
        if (cpuDifficulty != null || !vsCpu) this else copy(cpuDifficulty = default)
}

/** Pure builder for [MatchDetails], so the daily / bot detection is unit-testable. */
fun buildMatchDetails(
    ctx: GameContext,
    route: String?,
    options: LaunchOptions
): MatchDetails = MatchDetails(
    mode = ctx.activeMode,
    hasBots = ctx.players.any { it.isBot },
    cpuDifficulty = options.cpuDifficulty,
    daily = route != null && route.endsWith("-daily"),
    humanCount = ctx.players.count { !it.isBot }
)
