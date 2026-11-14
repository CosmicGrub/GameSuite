package com.gamesuite.games.slotcars

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

enum class SlotPhase { COUNTDOWN, RACING, OVER }

/**
 * The whole race physics: 1 dimension along the track, fixed 60 Hz steps, no clock and no random
 * numbers. Each car has a throttle that is either held or not; held it accelerates toward top
 * speed, released the motor brakes it. A car whose speed squared times the bend's curvature passes
 * [SlotCarsRules.A_MAX] leaves the slot, loses all its speed and lies off the track for
 * [SlotCarsRules.RESLOT_TICKS] before it is put back where it left.
 */
class SlotCarsSim(val track: SlotTrack, val laps: Int, val cars: Int) {
    var tick = 0
        internal set
    var phase = SlotPhase.COUNTDOWN
        internal set
    /** Total distance driven; the lap is floor(s / length). */
    val s = DoubleArray(cars)
    val v = DoubleArray(cars)
    val throttle = BooleanArray(cars)
    /** Ticks left lying off the track (0 = in the slot). */
    val off = IntArray(cars)
    val offS = DoubleArray(cars)
    val offV = DoubleArray(cars)
    val lapsDone = IntArray(cars)
    val lapStart = DoubleArray(cars)
    val lastLap = DoubleArray(cars) { -1.0 }
    val bestLap = DoubleArray(cars) { -1.0 }
    /** Finish time in ticks (fractional), -1 until the car has finished. */
    val finishTime = DoubleArray(cars) { -1.0 }
    val deslots = IntArray(cars)
    var firstFinishTick = -1
        internal set

    // Event counters the screen keys sounds and animations to.
    var deslotSerial = 0; internal set
    var lastDeslotCar = -1; internal set
    var lapSerial = 0; internal set
    var lastLapCar = -1; internal set
    var finishSerial = 0; internal set
    var lastFinishCar = -1; internal set

    val totalDistance: Double get() = laps * track.length

    /** Grip use 0..1+ of car [i] right now (1 = about to leave the slot). */
    fun gripUse(i: Int): Double = v[i] * v[i] * track.curvatureAt(s[i]) / SlotCarsRules.A_MAX

    /** One fixed step. Throttles are those set in [throttle]; they only count while racing. */
    fun step(authoritative: Boolean = true) {
        tick++
        when (phase) {
            SlotPhase.COUNTDOWN -> {
                if (tick >= SlotCarsRules.COUNTDOWN_TICKS) { phase = SlotPhase.RACING; tick = 0 }
                return
            }
            SlotPhase.OVER -> return
            SlotPhase.RACING -> {}
        }
        for (i in 0 until cars) stepCar(i)
        if (!authoritative) return
        if (firstFinishTick < 0 && finishTime.any { it >= 0 }) firstFinishTick = tick
        val allDone = finishTime.all { it >= 0 }
        val graceOver = firstFinishTick >= 0 && tick - firstFinishTick >= SlotCarsRules.FINISH_GRACE_TICKS
        if (allDone || graceOver || tick >= SlotCarsRules.MAX_RACE_TICKS) phase = SlotPhase.OVER
    }

    private fun stepCar(i: Int) {
        if (off[i] > 0) {
            off[i]--
            if (off[i] == 0) { v[i] = 0.0 }
            return
        }
        val done = finishTime[i] >= 0
        val thr = throttle[i] && !done
        val dt = SlotCarsRules.DT
        v[i] = if (thr) v[i] + SlotCarsRules.ACC * (1.0 - v[i] / SlotCarsRules.VMAX) * dt
        else v[i] - (SlotCarsRules.BRAKE + SlotCarsRules.DRAG * v[i]) * dt
        if (v[i] < 0) v[i] = 0.0
        if (!done && v[i] * v[i] * track.curvatureAt(s[i]) > SlotCarsRules.A_MAX) {
            off[i] = SlotCarsRules.RESLOT_TICKS; offS[i] = s[i]; offV[i] = v[i]
            v[i] = 0.0; deslots[i]++; deslotSerial++; lastDeslotCar = i
            return
        }
        val before = s[i]
        s[i] += v[i] * dt
        if (done) return
        val len = track.length
        val lapNow = floor2(s[i] / len)
        if (lapNow > lapsDone[i]) {
            val boundary = lapNow * len
            val frac = if (s[i] > before) (s[i] - boundary) / (s[i] - before) else 0.0
            val t = (tick - frac)
            val lapTicks = t - lapStart[i]
            lapStart[i] = t
            lapsDone[i] = lapNow
            lastLap[i] = lapTicks / SlotCarsRules.STEPS_PER_SECOND
            if (bestLap[i] < 0 || lastLap[i] < bestLap[i]) bestLap[i] = lastLap[i]
            lapSerial++; lastLapCar = i
            if (lapNow >= laps) { finishTime[i] = t; finishSerial++; lastFinishCar = i }
        }
    }

    private fun floor2(x: Double): Int = Math.floor(x).toInt()

    /** Where car [i] would be after [ticks] more steps with the throttle [on] all along: (distance, speed, leftTheSlot). */
    fun project(i: Int, on: Boolean, ticks: Int): Triple<Double, Double, Boolean> {
        var ss = s[i]; var vv = v[i]; var o = off[i]
        var left = false
        repeat(ticks) {
            if (o > 0) { o--; if (o == 0) vv = 0.0; return@repeat }
            vv = if (on) vv + SlotCarsRules.ACC * (1.0 - vv / SlotCarsRules.VMAX) * SlotCarsRules.DT
            else vv - (SlotCarsRules.BRAKE + SlotCarsRules.DRAG * vv) * SlotCarsRules.DT
            if (vv < 0) vv = 0.0
            if (vv * vv * track.curvatureAt(ss) > SlotCarsRules.A_MAX) { left = true; o = SlotCarsRules.RESLOT_TICKS; vv = 0.0; return@repeat }
            ss += vv * SlotCarsRules.DT
        }
        return Triple(ss, vv, left)
    }

    /** Finishing order, best first: finished cars by time, then the rest by distance. */
    fun order(): List<Int> = (0 until cars).sortedWith(
        compareBy<Int> { if (finishTime[it] >= 0) 0 else 1 }
            .thenBy { if (finishTime[it] >= 0) finishTime[it] else 0.0 }
            .thenByDescending { s[it] }
            .thenBy { it }
    )

    /** Order-sensitive digest of the dynamic state (determinism tests). */
    fun fingerprint(): Long {
        var h = 1125899906842597L
        fun mix(x: Long) { h = 31 * h + x }
        mix(tick.toLong()); mix(phase.ordinal.toLong())
        for (i in 0 until cars) {
            mix(s[i].toRawBits()); mix(v[i].toRawBits()); mix(off[i].toLong()); mix(lapsDone[i].toLong()); mix(deslots[i].toLong())
        }
        return h
    }
}

/** How a computer driver thinks. */
data class SlotCpuProfile(
    /** Fraction of the true bend speed it aims for. */
    val margin: Double,
    /** Random misjudgement of each stretch of track (fraction of the bend speed). */
    val sigma: Double,
    /** It re-decides every this many ticks (a slow driver reacts late). */
    val decisionTicks: Int,
    /** How much better than reality it thinks its brakes are (1 = exact; more brakes later). */
    val optimism: Double,
    /** Chance per stretch of track of a lapse: it misjudges the next bend badly (a human slip). */
    val lapse: Double = 0.0
)

object SlotCpu {
    fun profile(level: CpuDifficulty): SlotCpuProfile = when (level) {
        CpuDifficulty.EASY -> SlotCpuProfile(margin = 0.85, sigma = 0.08, decisionTicks = 8, optimism = 1.0, lapse = 0.012)
        CpuDifficulty.MEDIUM -> SlotCpuProfile(margin = 0.88, sigma = 0.05, decisionTicks = 4, optimism = 1.0, lapse = 0.02)
        CpuDifficulty.HARD -> SlotCpuProfile(margin = 0.965, sigma = 0.03, decisionTicks = 2, optimism = 1.0, lapse = 0.008)
    }

    /** The exact "perfect" driver used for hints. */
    val PERFECT = SlotCpuProfile(margin = 0.985, sigma = 0.0, decisionTicks = 1, optimism = 1.0)

    /**
     * Should a driver on [profile] hold the throttle at distance [s] doing speed [v]? It holds it when
     * its speed is below what it could still brake down from for every bend ahead; [noise] is its
     * current misjudgement of the track.
     */
    fun wantsThrottle(track: SlotTrack, s: Double, v: Double, profile: SlotCpuProfile, noise: Double): Boolean {
        val next = v + SlotCarsRules.ACC * (1.0 - v / SlotCarsRules.VMAX) * SlotCarsRules.DT * (profile.decisionTicks + 1)
        val decel = SlotCarsRules.BRAKE * profile.optimism + SlotCarsRules.DRAG * v * 0.5
        var d = 0.0
        var allowed = Double.MAX_VALUE
        while (d <= 420.0) {
            val vc = SlotCarsRules.criticalSpeed(track.curvatureAt(s + d)) * profile.margin * (1.0 + noise)
            val a = sqrt(vc * vc + 2.0 * decel * d)
            if (a < allowed) allowed = a
            d += 3.0
        }
        return next < allowed
    }

    /** Drives the computer seats of [sim] for one tick. [cars] lists which indices are computers. */
    class Driver(private val levels: (Int) -> CpuDifficulty, private val random: Random, cars: Int) {
        private val noise = DoubleArray(cars)
        private val noiseAt = DoubleArray(cars) { -1.0 }
        private val holding = BooleanArray(cars)

        fun drive(sim: SlotCarsSim, i: Int, reactionScale: Double = 1.0) {
            if (sim.phase != SlotPhase.RACING) return
            val prof = profile(levels(i))
            val every = max(1, (prof.decisionTicks * reactionScale).toInt())
            if (sim.s[i] - noiseAt[i] > 90.0 || noiseAt[i] < 0) {
                noiseAt[i] = sim.s[i]
                val r = (random.nextDouble() + random.nextDouble() - 1.0) // triangular, -1..1
                noise[i] = r * prof.sigma + (if (random.nextDouble() < prof.lapse) 0.3 else 0.0)
            }
            if (sim.tick % every == i % every) holding[i] = wantsThrottle(sim.track, sim.s[i], sim.v[i], prof, noise[i])
            sim.throttle[i] = holding[i] && sim.off[i] == 0
        }
    }

}
