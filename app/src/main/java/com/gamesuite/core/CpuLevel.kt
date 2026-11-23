package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty

/**
 * One shared four-step CPU level for every game's "vs Computer" mode (docs/roadmap/QUALITY-BAR.md gate 2).
 *
 * The engines only know the three-value [CpuDifficulty] (EASY, MEDIUM, HARD) carried by
 * [LaunchOptions.cpuDifficulty]. [EXPERT] is HARD plus a marker in [LaunchOptions.extras]
 * ([EXTRA_KEY] = "expert"), so nothing existing changes: an engine that ignores the marker simply
 * plays Hard. An engine that wants a stronger Expert reads [isExpert] and raises its search depth.
 */
enum class CpuLevel(val id: String, val difficulty: CpuDifficulty, val minThinkMillis: Long) {
    EASY("easy", CpuDifficulty.EASY, 450L),
    MEDIUM("medium", CpuDifficulty.MEDIUM, 600L),
    HARD("hard", CpuDifficulty.HARD, 800L),
    EXPERT("expert", CpuDifficulty.HARD, 1000L);

    val isExpert: Boolean get() = this == EXPERT

    /** [base] with this level applied (difficulty plus the Expert marker; other extras are kept). */
    fun applyTo(base: LaunchOptions): LaunchOptions {
        val extras = base.extras.toMutableMap()
        if (isExpert) extras[EXTRA_KEY] = id else extras.remove(EXTRA_KEY)
        return base.copy(cpuDifficulty = difficulty, extras = extras)
    }

    /** The Settings value to store when the player picks this level as their default. */
    fun asDefaultDifficulty(): CpuDifficulty = difficulty

    companion object {
        const val EXTRA_KEY = "cpu_level"

        fun fromId(id: String?): CpuLevel? = values().firstOrNull { it.id == id }

        fun fromDifficulty(d: CpuDifficulty): CpuLevel = when (d) {
            CpuDifficulty.EASY -> EASY
            CpuDifficulty.MEDIUM -> MEDIUM
            CpuDifficulty.HARD -> HARD
        }

        /** The level a launch asks for; with no override, the Settings default [fallback]. */
        fun from(options: LaunchOptions, fallback: CpuDifficulty = CpuDifficulty.MEDIUM): CpuLevel {
            val d = options.cpuDifficulty ?: return fromDifficulty(fallback)
            return if (d == CpuDifficulty.HARD && options.extras[EXTRA_KEY] == EXPERT.id) EXPERT else fromDifficulty(d)
        }
    }
}
