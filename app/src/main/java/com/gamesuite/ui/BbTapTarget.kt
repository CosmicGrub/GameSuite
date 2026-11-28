package com.gamesuite.ui

import kotlin.math.max

/** Smallest touch target Android accessibility guidance allows, in dp. */
internal const val BB_MIN_TARGET_DP = 48f

/**
 * Side of the square tap box laid over a Bot Bastion arena node. It follows the drawn node size
 * ([widthDp] / arena units * 1.15) on wide screens but never drops below [BB_MIN_TARGET_DP]
 * (on a 360 dp phone the old `min(48, ...)` shrank it to about 26 dp).
 */
internal fun bbNodeTapBoxDp(widthDp: Float, arenaWidthUnits: Double): Float {
    val scaled = if (arenaWidthUnits > 0.0) (widthDp / arenaWidthUnits.toFloat() * 1.15f) else 0f
    return max(BB_MIN_TARGET_DP, scaled)
}
