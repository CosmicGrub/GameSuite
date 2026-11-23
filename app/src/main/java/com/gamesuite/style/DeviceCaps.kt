package com.gamesuite.style

/** Raw facts read by the Android adapter (ui/DeviceCapsProbe.kt); [DeviceCaps.detect] is the pure, tested mapping. */
data class RawDeviceFacts(
    val apiLevel: Int,
    val totalRamBytes: Long,
    val lowRamFlag: Boolean,
    val batterySaver: Boolean,
    /** PowerManager.THERMAL_STATUS_* (0 none .. 6 shutdown); 0 below API 29. */
    val thermalStatus: Int,
    val animatorScale: Float
)

data class DeviceCaps(
    val apiLevel: Int,
    val agsl: Boolean,
    val ramClass: RamClass,
    val batterySaver: Boolean,
    val thermal: ThermalLevel,
    val osAnimationsOff: Boolean
) {
    companion object {
        /** A capable device with nothing limiting it: what previews and tests start from. */
        val CAPABLE = DeviceCaps(34, true, RamClass.HIGH, false, ThermalLevel.NONE, false)

        fun detect(raw: RawDeviceFacts): DeviceCaps = DeviceCaps(
            apiLevel = raw.apiLevel,
            agsl = raw.apiLevel >= 33,
            ramClass = when {
                raw.lowRamFlag || raw.totalRamBytes < StyleLimits.LOW_RAM_BYTES -> RamClass.LOW
                raw.totalRamBytes <= StyleLimits.HIGH_RAM_BYTES -> RamClass.MID
                else -> RamClass.HIGH
            },
            batterySaver = raw.batterySaver,
            thermal = when {
                raw.thermalStatus >= 3 -> ThermalLevel.SEVERE
                raw.thermalStatus == 2 -> ThermalLevel.MODERATE
                raw.thermalStatus == 1 -> ThermalLevel.LIGHT
                else -> ThermalLevel.NONE
            },
            osAnimationsOff = raw.animatorScale <= 0f
        )
    }
}
