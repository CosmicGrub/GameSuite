package com.gamesuite.games.territorywars

/**
 * Owner patterns: identity never rests on colour alone. Each seat has a fill colour AND a pattern drawn over the
 * territory AND its initial letter on the army chip. [labelKey] is the string resource name read by TalkBack.
 */
enum class OwnerPattern(val labelKey: String) {
    SOLID("tw_pattern_solid"),
    STRIPES("tw_pattern_stripes"),
    DOTS("tw_pattern_dots"),
    CROSSHATCH("tw_pattern_crosshatch"),
    BARS("tw_pattern_bars"),
    CHECKS("tw_pattern_checks")
}

/** The Midnight Club look of Territory Wars (design doc section 1.6). ARGB longs, like the rest of the theme code. */
object TerritoryWarsPalette {
    const val INK: Long = 0xFF0B0D1A
    const val INK_RAISED: Long = 0xFF141829
    const val IVORY: Long = 0xFFF2EEDF
    const val SAFFRON: Long = 0xFFFFB547

    /** The slate-blue accent: dark theme / light theme (contrast 5.77 on 1C1B1F and 7.83 on FFFBFE). */
    const val ACCENT_DARK: Long = 0xFF7F95CC
    const val ACCENT_LIGHT: Long = 0xFF3B4D8A

    /**
     * Seat colours (Okabe-Ito based, colour-blind-safe set): sky, orange, green, pink, yellow, vermilion.
     * Ink text on each has contrast of at least 5.0.
     */
    val SEAT_COLORS: List<Long> = listOf(0xFF56B4E9, 0xFFE69F00, 0xFF009E73, 0xFFCC79A7, 0xFFF0E442, 0xFFD55E00)

    fun colorOf(seat: Int): Long = SEAT_COLORS[((seat % SEAT_COLORS.size) + SEAT_COLORS.size) % SEAT_COLORS.size]

    fun patternOf(seat: Int): OwnerPattern {
        val all = OwnerPattern.values()
        return all[((seat % all.size) + all.size) % all.size]
    }
}
