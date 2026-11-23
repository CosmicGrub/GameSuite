package com.gamesuite.games.sixball

import kotlinx.serialization.Serializable

/**
 * Wire protocol of a networked Six-Ball Puzzle race (Nearby / Online). Everybody races the SAME level
 * at once, each on their own tray. Host-authoritative on the RESULTS: a guest never says "I finished
 * in N moves"; it sends the whole list of tilts it made ([Finish]) and the host replays them from the
 * level start, so a forged or impossible solution is simply refused. The host orders the results
 * (fewest tilts first; the host's own clock breaks a tie) and broadcasts them in a [StateSync].
 * Requests are honoured only for the transport-established sender; the host is roster seat 0.
 */
@Serializable
sealed class SixBallNetMessage {
    @Serializable
    data class StateSync(val version: Int, val race: SixBallNetRace) : SixBallNetMessage()

    /** "I solved it with exactly these tilts (codes 0..3)." */
    @Serializable
    data class Finish(val moves: List<Int>) : SixBallNetMessage()

    @Serializable
    data object GiveUp : SixBallNetMessage()

    @Serializable
    data object RequestState : SixBallNetMessage()

    /** Start the next level (host only acts on it, once the race is over). */
    @Serializable
    data object RequestPlayAgain : SixBallNetMessage()
}

@Serializable
data class SixBallNetRacer(val playerId: String, val status: String, val moves: Int)

@Serializable
data class SixBallNetRace(
    val levelId: String,
    val round: Int,
    val racers: List<SixBallNetRacer>,
    val raceOver: Boolean,
    val winnerIds: List<String>
)
