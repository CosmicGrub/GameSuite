package com.gamesuite.haptics

/**
 * One vibration waveform as plain data (no Android types), so every pattern can be checked by a JVM
 * unit test. The format is the one `VibrationEffect.createWaveform` takes: [timings] and [amplitudes]
 * are parallel arrays, entry i being "hold amplitude i for timing i milliseconds". An amplitude of 0 is
 * a pause; 1..255 is motor strength. By convention entry 0 is a zero-amplitude, zero-length start.
 */
class HapticPattern(val timings: LongArray, val amplitudes: IntArray) {
    /** Total length of the waveform in milliseconds. */
    val totalMs: Long get() = timings.sum()

    /** Content equality (arrays do not compare by content on their own). */
    fun sameAs(other: HapticPattern): Boolean =
        timings.contentEquals(other.timings) && amplitudes.contentEquals(other.amplitudes)
}

/**
 * THE TUNING TABLE for every [HapticSignal]. Edit the numbers here to change how a signal feels; the
 * Android side ([performHaptic]) is a thin adapter that hands these arrays to the vibrator. Rules the
 * unit tests enforce: non-empty, parallel arrays, amplitudes 0..255, under 400 ms (CELEBRATION under
 * 600 ms), and no two signals share a waveform.
 *
 * Reading a row: `longArrayOf(0, 25, 40, 25)` with `intArrayOf(0, 160, 0, 190)` = start, 25 ms buzz at
 * strength 160, 40 ms gap, 25 ms buzz at strength 190.
 */
object HapticPatterns {
    /** Upper bound on any non-celebration pattern, checked by the tests. */
    const val MAX_MS = 400L

    /** Upper bound for [HapticSignal.CELEBRATION]. */
    const val MAX_CELEBRATION_MS = 600L

    fun patternFor(signal: HapticSignal): HapticPattern = when (signal) {
        HapticSignal.LIGHT_TICK -> HapticPattern(longArrayOf(0, 12), intArrayOf(0, 90))
        HapticSignal.NORMAL_ACTION -> HapticPattern(longArrayOf(0, 25), intArrayOf(0, 140))
        HapticSignal.STRONG_ACTION -> HapticPattern(longArrayOf(0, 20, 40, 30), intArrayOf(0, 200, 0, 220))
        HapticSignal.ESCALATING -> HapticPattern(
            longArrayOf(0, 15, 60, 15, 60, 20), intArrayOf(0, 120, 0, 160, 0, 200)
        )
        HapticSignal.SUCCESS -> HapticPattern(longArrayOf(0, 18, 50, 28), intArrayOf(0, 150, 0, 220))
        HapticSignal.FAILURE -> HapticPattern(longArrayOf(0, 60, 40, 60), intArrayOf(0, 180, 0, 140))
        HapticSignal.CELEBRATION -> HapticPattern(
            longArrayOf(0, 25, 40, 25, 40, 25, 40, 60), intArrayOf(0, 160, 0, 190, 0, 220, 0, 255)
        )
        // Dice in a cup: five uneven taps close together (~118 ms).
        HapticSignal.ROLL -> HapticPattern(
            longArrayOf(0, 10, 18, 10, 14, 12, 20, 8, 12, 14),
            intArrayOf(0, 110, 0, 150, 0, 120, 0, 170, 0, 130)
        )
        // A soft, slightly longer and lower thump than NORMAL_ACTION: weight settling, not a click.
        HapticSignal.LANDING -> HapticPattern(longArrayOf(0, 40), intArrayOf(0, 105))
        // The faintest, shortest thing the motor can do; meant to be chained at 40-60 ms spacing.
        HapticSignal.RIPPLE_STEP -> HapticPattern(longArrayOf(0, 8), intArrayOf(0, 55))
    }
}
