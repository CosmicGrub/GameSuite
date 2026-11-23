package com.gamesuite.games.slotcars

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Slot Cars over Nearby / Online, 2 to 4 seats. Host-authoritative: the host
 * (seat 0) owns the only simulation and the only clock. A guest sends what it WANTS ([Throttle],
 * [Ready]); the seat it acts for is NEVER in the payload, it is the seat the transport established
 * for the sender. The host answers with versioned [StateSync] snapshots (about 10 a second during a
 * race, at once on any event). Between snapshots a guest carries the same deterministic simulation
 * forward from the last one, so a late packet costs a little smoothness, never fairness.
 * Nothing is hidden information.
 */
@Serializable
sealed class SlotCarsNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: SlotNetState) : SlotCarsNetMessage()

    /** "My throttle is now held / released." In the stepwise assist it sets the plan for the next step. */
    @Serializable
    data class Throttle(val on: Boolean) : SlotCarsNetMessage()

    /** Stepwise assist only: "my plan for the next step is final, run it." */
    @Serializable
    data class Ready(val ready: Boolean) : SlotCarsNetMessage()

    @Serializable
    data object RequestState : SlotCarsNetMessage()

    @Serializable
    data object RequestPlayAgain : SlotCarsNetMessage()
}

@Serializable
data class SlotNetPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

@Serializable
data class SlotNetCar(
    val s: Double, val v: Double, val throttle: Boolean, val off: Int, val offS: Double, val offV: Double,
    val lapsDone: Int, val lapStart: Double, val lastLap: Double, val bestLap: Double, val finishTime: Double, val deslots: Int
)

@Serializable
data class SlotNetState(
    val players: List<SlotNetPlayer>,
    val track: String,
    val laps: Int,
    val assist: String,
    val phase: String,
    val tick: Int,
    val firstFinishTick: Int,
    val cars: List<SlotNetCar>,
    val ready: List<Boolean>,
    val stepRemaining: Int,
    val deslotSerial: Int,
    val lastDeslotCar: Int,
    val lapSerial: Int,
    val lastLapCar: Int,
    val finishSerial: Int,
    val lastFinishCar: Int,
    val sessionWins: List<Int>,
    val sessionPoints: List<Int> = emptyList(),
    val gone: List<Int> = emptyList()
)
