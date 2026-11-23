package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty

/**
 * The tiny seam the Computer Arena uses on a game module (null on every normal game, so there is no
 * behaviour change outside the arena). Two jobs:
 *  - [levelFor]: a CPU level PER SEAT (Computer A and Computer B can differ), consulted where a bot search
 *    starts, instead of the single `difficulty` of the module;
 *  - pause: while [paused] a computer move that has been computed is HELD ([deferIfPaused]) and applied the
 *    moment [setPaused] (false) is called, so the screen's own bot loop needs no knowledge of pausing.
 * Everything runs on the main thread except [levelFor], which only reads an immutable map.
 */
class ArenaHook(levels: Map<Int, CpuDifficulty>) {
    @Volatile
    private var seatLevels: Map<Int, CpuDifficulty> = levels

    @Volatile
    var paused: Boolean = false
        private set

    private var held: (() -> Unit)? = null

    fun levelFor(seat: Int, fallback: CpuDifficulty): CpuDifficulty = seatLevels[seat] ?: fallback

    /** True when [action] was parked because the arena is paused (it runs at resume); false means run it now. */
    fun deferIfPaused(action: () -> Unit): Boolean {
        if (!paused) return false
        held = action
        return true
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        if (!value) {
            val run = held
            held = null
            run?.invoke()
        }
    }

    /** Drops a parked move (the arena moved on to another game). */
    fun clearHeld() {
        held = null
    }
}
