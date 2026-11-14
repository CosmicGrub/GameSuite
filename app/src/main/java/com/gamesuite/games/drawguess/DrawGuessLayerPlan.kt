package com.gamesuite.games.drawguess

/**
 * Decides how the cached bitmap of finished strokes is brought up to date (pure, so it is unit tested). The screen
 * keeps one bitmap of every committed stroke and draws only the live stroke per frame; this says whether the bitmap can
 * just get the new strokes painted on top ([Plan.from] onward), or must be wiped and rebuilt ([Plan.reset]: undo, clear,
 * a new paper size, or any change to strokes already painted).
 */
object StrokeLayerPlanner {
    data class Plan(val reset: Boolean, val from: Int, val total: Int) {
        /** True when there is anything to paint onto the bitmap. */
        val hasWork: Boolean get() = reset || from < total
    }

    /** [rendered] is what the bitmap holds now, [current] what the board holds; strokes are compared by identity. */
    fun <T : Any> plan(rendered: List<T>, current: List<T>, sizeChanged: Boolean): Plan {
        if (sizeChanged) return Plan(reset = true, from = 0, total = current.size)
        if (rendered.size > current.size) return Plan(reset = true, from = 0, total = current.size)
        for (i in rendered.indices) if (rendered[i] !== current[i]) return Plan(reset = true, from = 0, total = current.size)
        return Plan(reset = false, from = rendered.size, total = current.size)
    }
}
